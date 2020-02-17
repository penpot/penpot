;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.images
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [datoteka.storages :as ds]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.tasks :as tasks]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.data :as data]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.storage :as ust]
   [vertx.util :as vu]))

(def thumbnail-options
  {:width 800
   :height 800
   :quality 80
   :format "webp"})

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::collection-id ::us/uuid)



;; --- Create Collection

(declare create-image-collection)

(s/def ::create-image-collection
  (s/keys :req-un [::profile-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-image-collection
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (create-image-collection conn params)))

(def ^:private sql:create-image-collection
  "insert into image_collection (id, profile_id, name)
   values ($1, $2, $3)
   returning *;")

(defn- create-image-collection
  [conn {:keys [id profile-id name] :as params}]
  (let [id (or id (uuid/next))]
    (db/query-one conn [sql:create-image-collection id profile-id name])))



;; --- Collection Permissions Check

(def ^:private sql:select-collection
  "select id, profile_id
     from image_collection
    where id=$1 and deleted_at is null
      for update")

(defn- check-collection-edition-permissions!
  [conn profile-id coll-id]
  (p/let [coll (-> (db/query-one conn [sql:select-collection coll-id])
                   (p/then' su/raise-not-found-if-nil))]
    (when (not= (:profile-id coll) profile-id)
      (ex/raise :type :validation
                :code :not-authorized))))



;; --- Rename Collection

(def ^:private sql:rename-image-collection
  "update image_collection
      set name = $2
    where id = $1
   returning *;")

(s/def ::rename-image-collection
  (s/keys :req-un [::id ::profile-id ::us/name]))

(sm/defmutation ::rename-image-collection
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (check-collection-edition-permissions! conn profile-id id)
    (db/query-one conn [sql:rename-image-collection id name])))



;; --- Delete Collection

(s/def ::delete-image-collection
  (s/keys :req-un [::profile-id ::id]))

(def ^:private sql:mark-image-collection-as-deleted
  "update image_collection
      set deleted_at = clock_timestamp()
    where id = $1
   returning id")

(sm/defmutation ::delete-image-collection
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-collection-edition-permissions! conn profile-id id)

    ;; Schedule object deletion
    (tasks/schedule! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :image-collection}})

    (-> (db/query-one conn [sql:mark-image-collection-as-deleted id])
        (p/then' su/raise-not-found-if-nil)
        (p/then' su/constantly-nil))))



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
  (s/keys :req-un [::profile-id ::name ::content ::collection-id]
          :opt-un [::id]))

(sm/defmutation ::upload-image
  [{:keys [collection-id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-collection-edition-permissions! conn profile-id collection-id)
    (create-image conn params)))

(def ^:private sql:insert-image
  "insert into image
      (id, collection_id, profile_id, name, path, width, height, mtype,
       thumb_path, thumb_width, thumb_height, thumb_quality, thumb_mtype)
   values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
   returning *")

(defn create-image
  [conn {:keys [id content collection-id profile-id name] :as params}]
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
                collection-id
                profile-id
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
  [{:keys [name path] :as upload}]
  (vu/blocking
   (let [filename (fs/name name)]
     (ust/save! media/media-storage filename path))))

(defn persist-image-thumbnail-on-fs
  [thumb-opts input-path]
  (vu/blocking
   (let [input-path (ust/lookup media/media-storage input-path)
         thumb-data (images/generate-thumbnail input-path thumb-opts)
         [filename ext] (fs/split-ext (fs/name input-path))
         thumb-name (->> (images/format->extension (:format thumb-opts))
                         (str "thumbnail-" filename))]
     (ust/save! media/media-storage thumb-name thumb-data))))



;; --- Update Image

(s/def ::update-image
  (s/keys :req-un [::id ::profile-id ::name ::collection-id]))

(def ^:private sql:update-image
  "update image
      set name = $3,
          collection_id = $2
    where id = $1
      and profile_id = $4
   returning *;")

(sm/defmutation ::update-image
  [{:keys [id name profile-id collection-id] :as params}]
  (-> (db/query-one db/pool [sql:update-image id
                             collection-id name profile-id])
      (p/then' su/raise-not-found-if-nil)))


;; --- Copy Image

;; (declare retrieve-image)

;; (s/def ::copy-image
;;   (s/keys :req-un [::id ::collection-id ::profile-id]))

;; (sm/defmutation ::copy-image
;;   [{:keys [profile-id id collection-id] :as params}]
;;   (letfn [(copy-image [conn {:keys [path] :as image}]
;;             (-> (ds/lookup media/images-storage (:path image))
;;                 (p/then (fn [path] (ds/save media/images-storage (fs/name path) path)))
;;                 (p/then (fn [path]
;;                           (-> image
;;                               (assoc :path (str path) :collection-id collection-id)
;;                               (dissoc :id))))
;;                 (p/then (partial store-image-in-db conn))))]

;;     (db/with-atomic [conn db/pool]
;;       (-> (retrieve-image conn {:id id :profile-id profile-id})
;;           (p/then su/raise-not-found-if-nil)
;;           (p/then (partial copy-image conn))))))

;; --- Delete Image

(def ^:private sql:mark-image-deleted
  "update image
      set deleted_at = clock_timestamp()
    where id = $1
      and profile_id = $2
    returning id")

(s/def ::delete-image
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-image
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (-> (db/query-one conn [sql:mark-image-deleted id profile-id])
        (p/then' su/raise-not-found-if-nil))

    ;; Schedule object deletion
    (tasks/schedule! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :image}})

    nil))


