;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.services.mutations.images
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.media :as media]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries.teams :as teams]
   [uxbox.tasks :as tasks]
   [uxbox.util.storage :as ust]
   [uxbox.util.time :as dt]))

(def thumbnail-options
  {:width 800
   :height 800
   :quality 85
   :format "jpeg"})

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

(defn create-library
  [conn {:keys [id team-id name]}]
  (let [id (or id (uuid/next))]
    (db/insert! conn :image-library
                {:id id
                 :team-id team-id
                 :name name})))


;; --- Rename Library

(declare select-library-for-update)

(s/def ::rename-image-library
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-image-library
  [{:keys [profile-id id name] :as params}]
  (db/with-atomic [conn db/pool]
    (let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (db/update! conn :image-library
                  {:name name}
                  {:id id}))))

(defn- select-library-for-update
  [conn id]
  (db/get-by-id conn :image-library id {:for-update true}))


;; --- Delete Library

(declare delete-library)

(s/def ::delete-image-library
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-image-library
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [lib (select-library-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))

      ;; Schedule object deletion
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :image-library}})

      (db/update! conn :image-library
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))



;; --- Create Image (Upload)

(declare create-image)
(declare persist-image-on-fs)
(declare persist-image-thumbnail-on-fs)

(def valid-image-types?
  #{"image/jpeg", "image/png", "image/webp"})

(s/def :uxbox$upload/filename ::us/string)
(s/def :uxbox$upload/size ::us/integer)
(s/def :uxbox$upload/content-type valid-image-types?)
(s/def :uxbox$upload/tempfile any?)

(s/def ::upload
  (s/keys :req-un [:uxbox$upload/filename
                   :uxbox$upload/size
                   :uxbox$upload/tempfile
                   :uxbox$upload/content-type]))

(s/def ::content ::upload)

(s/def ::upload-image
  (s/keys :req-un [::profile-id ::name ::content ::library-id]
          :opt-un [::id]))

(sm/defmutation ::upload-image
  [{:keys [library-id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [lib (select-library-for-update conn library-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (create-image conn params))))

(defn create-image
  [conn {:keys [id content library-id name]}]
  (when-not (valid-image-types? (:content-type content))
    (ex/raise :type :validation
              :code :image-type-not-allowed
              :hint "Seems like you are uploading an invalid image."))

  (let [image-opts (images/info (:content-type content) (:tempfile content))
        image-path (persist-image-on-fs content)
        thumb-opts thumbnail-options
        thumb-path (persist-image-thumbnail-on-fs thumb-opts image-path)]
    (-> (db/insert! conn :image
                    {:id (or id (uuid/next))
                     :library-id library-id
                     :name name
                     :path (str image-path)
                     :width (:width image-opts)
                     :height (:height image-opts)
                     :mtype  (:content-type content)
                     :thumb-path (str thumb-path)
                     :thumb-width (:width thumb-opts)
                     :thumb-height (:height thumb-opts)
                     :thumb-quality (:quality thumb-opts)
                     :thumb-mtype (images/format->mtype (:format thumb-opts))})
        (images/resolve-urls :path :uri)
        (images/resolve-urls :thumb-path :thumb-uri))))

(defn persist-image-on-fs
  [{:keys [filename tempfile]}]
  (let [filename (fs/name filename)]
    (ust/save! media/media-storage filename tempfile)))

(defn persist-image-thumbnail-on-fs
  [thumb-opts input-path]
  (let [input-path (ust/lookup media/media-storage input-path)
        thumb-data (images/generate-thumbnail input-path thumb-opts)
        [filename _] (fs/split-ext (fs/name input-path))
        thumb-name (->> (images/format->extension (:format thumb-opts))
                         (str "thumbnail-" filename))]
    (ust/save! media/media-storage thumb-name thumb-data)))


;; --- Mutation: Rename Image

(declare select-image-for-update)

(s/def ::rename-image
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-image
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (let [img (select-image-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id img))
      (db/update! conn :image
                  {:name name}
                  {:id id}))))

(def ^:private sql:select-image-for-update
  "select img.*,
          lib.team_id as team_id
     from image as img
    inner join image_library as lib on (lib.id = img.library_id)
    where img.id = ?
      for update of img")

(defn- select-image-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-image-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))



;; --- Delete Image

(s/def ::delete-image
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-image
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [img (select-image-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id img))

      ;; Schedule object deletion
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :image}})

      (db/update! conn :image
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))
