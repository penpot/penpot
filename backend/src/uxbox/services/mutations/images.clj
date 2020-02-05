;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
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
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
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
(s/def ::user ::us/uuid)

;; --- Create Collection

(declare create-images-collection)

(s/def ::create-images-collection
  (s/keys :req-un [::user ::us/name]
          :opt-un [::id]))

(sm/defmutation ::create-images-collection
  [{:keys [id user name] :as params}]
  (db/with-atomic [conn db/pool]
    (create-images-collection conn params)))

(defn create-images-collection
  [conn {:keys [id user name] :as params}]
  (let [id  (or id (uuid/next))
        sql "insert into image_collections (id, user_id, name)
             values ($1, $2, $3)
             on conflict (id) do nothing
             returning *;"]
    (db/query-one db/pool [sql id user name])))

;; --- Update Collection

(def ^:private
  sql:rename-images-collection
  "update image_collections
      set name = $3
    where id = $1
      and user_id = $2
   returning *;")

(s/def ::rename-images-collection
  (s/keys :req-un [::id ::user ::us/name]))

(sm/defmutation ::rename-images-collection
  [{:keys [id user name] :as params}]
  (db/with-atomic [conn db/pool]
    (db/query-one conn [sql:rename-images-collection id user name])))

;; --- Delete Collection

(s/def ::delete-images-collection
  (s/keys :req-un [::user ::id]))

(def ^:private
  sql:delete-images-collection
  "update image_collections
      set deleted_at = clock_timestamp()
    where id = $1
      and user_id = $2
   returning id")

(sm/defmutation ::delete-images-collection
  [{:keys [id user] :as params}]
  (-> (db/query-one db/pool [sql:delete-images-collection id user])
      (p/then' su/raise-not-found-if-nil)))

;; --- Create Image (Upload)

(declare select-collection-for-update)
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

(s/def ::collection-id ::us/uuid)
(s/def ::content ::upload)

(s/def ::upload-image
  (s/keys :req-un [::user ::name ::content ::collection-id]
          :opt-un [::id]))

(sm/defmutation ::upload-image
  [{:keys [collection-id user] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [coll (select-collection-for-update conn collection-id)]
      (when (not= (:user-id coll) user)
        (ex/raise :type :validation
                  :code :not-authorized))
      (create-image conn params))))

(def ^:private sql:insert-image
  "insert into images
      (id, collection_id, user_id, name, path, width, height, mtype,
       thumb_path, thumb_width, thumb_height, thumb_quality, thumb_mtype)
   values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
   returning *")

(defn create-image
  [conn {:keys [id content collection-id user name] :as params}]
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
                user
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

(defn- select-collection-for-update
  [conn id]
  (let [sql "select c.id, c.user_id
               from image_collections as c
              where c.id = $1
                and c.deleted_at is null
                 for update;"]
    (-> (db/query-one conn [sql id])
        (p/then' su/raise-not-found-if-nil))))

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
  (s/keys :req-un [::id ::user ::name ::collection-id]))

(def ^:private sql:update-image
  "update images
      set name = $3,
          collection_id = $2
    where id = $1
      and user_id = $4
   returning *;")

(sm/defmutation ::update-image
  [{:keys [id name user collection-id] :as params}]
  (db/query-one db/pool [sql:update-image id collection-id name user]))

;; --- Copy Image

(declare retrieve-image)

;; (s/def ::copy-image
;;   (s/keys :req-un [::id ::collection-id ::user]))

;; (sm/defmutation ::copy-image
;;   [{:keys [user id collection-id] :as params}]
;;   (letfn [(copy-image [conn {:keys [path] :as image}]
;;             (-> (ds/lookup media/images-storage (:path image))
;;                 (p/then (fn [path] (ds/save media/images-storage (fs/name path) path)))
;;                 (p/then (fn [path]
;;                           (-> image
;;                               (assoc :path (str path) :collection-id collection-id)
;;                               (dissoc :id))))
;;                 (p/then (partial store-image-in-db conn))))]

;;     (db/with-atomic [conn db/pool]
;;       (-> (retrieve-image conn {:id id :user user})
;;           (p/then su/raise-not-found-if-nil)
;;           (p/then (partial copy-image conn))))))

;; --- Delete Image

;; TODO: this need to be performed in the GC process
;; (defn- delete-image-from-storage
;;   [{:keys [path] :as image}]
;;   (when @(ds/exists? media/images-storage path)
;;     @(ds/delete media/images-storage path))
;;   (when @(ds/exists? media/thumbnails-storage path)
;;     @(ds/delete media/thumbnails-storage path)))

(s/def ::delete-image
  (s/keys :req-un [::id ::user]))

(sm/defmutation ::delete-image
  [{:keys [user id] :as params}]
  (let [sql "update images
                set deleted_at = clock_timestamp()
              where id = $1
                and user_id = $2
             returning *"]
    (db/query-one db/pool [sql id user])))
