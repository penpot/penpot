;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.images
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.tasks :as tasks]
   [uxbox.services.queries.teams :as teams]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.storage :as ust]
   [vertx.util :as vu]))

(def thumbnail-options
  {:width 800
   :height 800
   :quality 85
   :format "webp"})

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::library-id ::us/uuid)
(s/def ::team-id ::us/uuid)


;; --- Create Library

(declare create-library)

(s/def ::create-image-library
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-image-library
  [{:keys [profile-id team-id] :as params}]
  (db/with-atomic [conn db/pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (create-library conn params)))

(def ^:private sql:create-library
  "insert into image_library (id, team_id, name)
   values ($1, $2, $3)
   returning *;")

(defn- create-library
  [conn {:keys [id team-id name]}]
  (let [id (or id (uuid/next))]
    (db/query-one conn [sql:create-library id team-id name])))


;; --- Rename Library

(declare select-library-for-update)
(declare rename-library)

(s/def ::rename-image-library
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-image-library
  [{:keys [profile-id id name] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (rename-library conn id name))))

(def ^:private sql:select-library-for-update
  "select l.*
     from image_library as l
    where l.id = $1
      for update")

(def ^:private sql:rename-library
  "update image_library
      set name = $2
    where id = $1")

(defn- select-library-for-update
  [conn id]
  (-> (db/query-one conn [sql:select-library-for-update id])
      (p/then' su/raise-not-found-if-nil)))

(defn- rename-library
  [conn id name]
  (-> (db/query-one conn [sql:rename-library id name])
      (p/then' su/constantly-nil)))



;; --- Delete Library

(declare delete-library)

(s/def ::delete-image-library
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-image-library
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :image-library}})

      (delete-library conn id))))

(def ^:private sql:mark-library-deleted
  "update image_library
      set deleted_at = clock_timestamp()
    where id = $1")

(defn- delete-library
  [conn id]
  (-> (db/query-one conn [sql:mark-library-deleted id])
      (p/then' su/constantly-nil)))



;; --- Create Image (Upload)

(declare create-image)
(declare persist-image-on-fs)
(declare persist-image-thumbnail-on-fs)

(def valid-image-types?
  #{"image/jpeg", "image/png", "image/webp"})

(s/def :uxbox$upload/name ::us/string)
(s/def :uxbox$upload/size ::us/integer)
(s/def :uxbox$upload/mtype valid-image-types?)
(s/def :uxbox$upload/path ::us/string)

(s/def ::upload
  (s/keys :req-un [:uxbox$upload/name
                   :uxbox$upload/size
                   :uxbox$upload/path
                   :uxbox$upload/mtype]))

(s/def ::content ::upload)

(s/def ::upload-image
  (s/keys :req-un [::profile-id ::name ::content ::library-id]
          :opt-un [::id]))

(sm/defmutation ::upload-image
  [{:keys [library-id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [lib (select-library-for-update conn library-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (create-image conn params))))

(def ^:private sql:insert-image
  "insert into image
      (id, library_id, name, path, width, height, mtype,
       thumb_path, thumb_width, thumb_height, thumb_quality, thumb_mtype)
   values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
   returning *")

(defn create-image
  [conn {:keys [id content library-id name]}]
  (when-not (valid-image-types? (:mtype content))
    (ex/raise :type :validation
              :code :image-type-not-allowed
              :hint "Seems like you are uploading an invalid image."))
  (p/let [image-opts (vu/blocking (images/info (:path content)))
          image-path (persist-image-on-fs content)
          thumb-opts thumbnail-options
          thumb-path (persist-image-thumbnail-on-fs thumb-opts image-path)
          id         (or id (uuid/next))

          sqlv [sql:insert-image
                id
                library-id
                name
                (str image-path)
                (:width image-opts)
                (:height image-opts)
                (:mtype content)
                (str thumb-path)
                (:width thumb-opts)
                (:height thumb-opts)
                (:quality thumb-opts)
                (images/format->mtype (:format thumb-opts))]]

    (-> (db/query-one conn sqlv)
        (p/then' #(images/resolve-urls % :path :uri))
        (p/then' #(images/resolve-urls % :thumb-path :thumb-uri)))))

(defn persist-image-on-fs
  [{:keys [name path]}]
  (vu/blocking
   (let [filename (fs/name name)]
     (ust/save! media/media-storage filename path))))

(defn persist-image-thumbnail-on-fs
  [thumb-opts input-path]
  (vu/blocking
   (let [input-path (ust/lookup media/media-storage input-path)
         thumb-data (images/generate-thumbnail input-path thumb-opts)
         [filename _] (fs/split-ext (fs/name input-path))
         thumb-name (->> (images/format->extension (:format thumb-opts))
                         (str "thumbnail-" filename))]
     (ust/save! media/media-storage thumb-name thumb-data))))



;; --- Mutation: Rename Image

(declare select-image-for-update)
(declare rename-image)

(s/def ::rename-image
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-image
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [img (select-image-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id img))
      (rename-image conn id name))))

(def ^:private sql:select-image-for-update
  "select img.*,
          lib.team_id as team_id
     from image as img
    inner join image_library as lib on (lib.id = img.library_id)
    where img.id = $1
      for update of img")

(def ^:private sql:rename-image
  "update image
      set name = $2
    where id = $1")

(defn- select-image-for-update
  [conn id]
  (-> (db/query-one conn [sql:select-image-for-update id])
      (p/then' su/raise-not-found-if-nil)))

(defn- rename-image
  [conn id name]
  (-> (db/query-one conn [sql:rename-image id name])
      (p/then' su/constantly-nil)))



;; --- Copy Image

;; (declare retrieve-image)

;; (s/def ::copy-image
;;   (s/keys :req-un [::id ::library-id ::profile-id]))

;; (sm/defmutation ::copy-image
;;   [{:keys [profile-id id library-id] :as params}]
;;   (letfn [(copy-image [conn {:keys [path] :as image}]
;;             (-> (ds/lookup media/images-storage (:path image))
;;                 (p/then (fn [path] (ds/save media/images-storage (fs/name path) path)))
;;                 (p/then (fn [path]
;;                           (-> image
;;                               (assoc :path (str path) :library-id library-id)
;;                               (dissoc :id))))
;;                 (p/then (partial store-image-in-db conn))))]

;;     (db/with-atomic [conn db/pool]
;;       (-> (retrieve-image conn {:id id :profile-id profile-id})
;;           (p/then su/raise-not-found-if-nil)
;;           (p/then (partial copy-image conn))))))


;; --- Delete Image

(declare delete-image)

(s/def ::delete-image
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-image
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [img (select-image-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id img))

      ;; Schedule object deletion
      (tasks/schedule! conn {:name "delete-object"
                             :delay cfg/default-deletion-delay
                             :props {:id id :type :image}})

      (delete-image conn id))))

(def ^:private sql:mark-image-deleted
  "update image
      set deleted_at = clock_timestamp()
    where id = $1")

(defn- delete-image
  [conn id]
  (-> (db/query-one conn [sql:mark-image-deleted id])
      (p/then' su/constantly-nil)))
