;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.images
  "Images library related services."
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [datoteka.storages :as ds]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.services.core :as sc]
   [uxbox.util.blob :as blob]
   [uxbox.util.data :as data]
   [uxbox.util.exceptions :as ex]
   [uxbox.util.spec :as us]
   [uxbox.util.uuid :as uuid]
   [vertx.core :as vc]))

(def +thumbnail-options+
  {:src :path
   :dst :thumbnail
   :width 300
   :height 100
   :quality 92
   :format "webp"})

(defn- populate-thumbnail
  [row]
  (let [opts +thumbnail-options+]
    (-> (px/submit! #(images/populate-thumbnails row opts))
        (sc/handle-on-context))))

(defn- populate-thumbnails
  [rows]
  (if (empty? rows)
    rows
    (p/all (map populate-thumbnail rows))))

(defn- populate-urls
  [row]
  (images/populate-urls row media/images-storage :path :url))

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::user ::us/uuid)
(s/def ::collection-id (s/nilable ::us/uuid))

;; --- Create Collection

(s/def ::create-image-collection
  (s/keys :req-un [::user ::us/name]
          :opt-un [::id]))

(sc/defmutation :create-image-collection
  {:doc "Create image collection"
   :spec ::create-image-collection}
  [{:keys [id user name] :as params}]
  (let [sql "insert into images_collections (id, user_id, name)
             values ($1, $2, $3) returning *;"]
    (db/query-one db/pool [sql (or id (uuid/next)) user name])))

;; --- Update Collection

(s/def ::update-images-collection
  (s/keys :req-un [::id ::user ::us/name]))

(sc/defmutation :update-images-collection
  {:doc "Update image collection."
   :spec ::update-images-collection}
  [{:keys [id user name] :as params}]
  (let [sql "update images_collections
                set name = $3
              where id = $1
                and user_id = $2
             returning *;"]
    (db/query-one db/pool [sql id user name])))

;; --- List Collections

(def ^:private images-collections-sql
  "select *,
          (select count(*) from images where collection_id = ic.id) as num_images
     from images_collections as ic
    where (ic.user_id = $1 or
           ic.user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and ic.deleted_at is null
    order by ic.created_at desc;")

(sc/defquery :images-collections
  {:doc "Retrieve image collections for the current logged user"}
  [{:keys [user] :as params}]
  (db/query db/pool [images-collections-sql user]))

;; --- Delete Collection

(s/def ::delete-images-collection
  (s/keys :req-un [::user ::id]))

(sc/defmutation :delete-images-collection
  {:doc "Delete an image collection"
   :spec ::delete-images-collection}
  [{:keys [id user] :as params}]
  (let [sql "update images_collections
                set deleted_at = clock_timestamp()
              where id = $1
                and user_id = $2
             returning id"]
    (-> (db/query-one db/pool [sql id user])
        (p/then' sc/raise-not-found-if-nil))))

;; --- Retrieve Image

(defn retrieve-image
  [conn id]
  (let [sql "select * from images
              where id = $1
                and deleted_at is null;"]
    (db/query-one conn [sql id])))

;; (s/def ::retrieve-image
;;   (s/keys :req-un [::user ::us/id]))

;; (defmethod core/query :retrieve-image
;;   [params]
;;   (s/assert ::retrieve-image params)
;;   (with-open [conn (db/connection)]
;;     (retrieve-image conn params)))

;; --- Create Image (Upload)

(defn- store-image-in-fs
  [{:keys [name path] :as upload}]
  (prn "store-image-in-fs" upload)
  (let [filename (fs/name name)
        storage media/images-storage]
    (-> (ds/save storage filename path)
        (vc/handle-on-context))))

(def ^:private create-image-sql
  "insert into images (user_id, name, collection_id, path, width, height, mimetype)
   values ($1, $2, $3, $4, $5, $6, $7) returning *")

(defn- store-image-in-db
  [conn {:keys [id user name path collection-id height width mimetype]}]
  (let [sqlv [create-image-sql user name collection-id
              path width height mimetype]]
    (-> (db/query-one conn sqlv)
        (p/then populate-thumbnail)
        (p/then populate-urls))))

(def valid-image-types?
  #{"image/jpeg", "image/png", "image/webp"})

(s/def ::file ::us/upload)
(s/def ::width ::us/integer)
(s/def ::height ::us/integer)
(s/def ::mimetype valid-image-types?)

(s/def ::create-image
  (s/keys :req-un [::user ::name ::file ::width ::height ::mimetype]
          :opt-un [::id ::collection-id]))

(sc/defmutation :create-image
  {:doc "Create (upload) new image."
   :spec ::create-image}
  [{:keys [file] :as params}]
  (when-not (valid-image-types? (:mtype file))
    (ex/raise :type :validation
              :code :image-type-not-allowed
              :hint "Seems like you are uploading an invalid image."))
  (-> (store-image-in-fs file)
      (p/then (fn [path]
                (store-image-in-db db/pool (assoc params :path (str path)))))))

;; --- Update Image

(s/def ::update-image
  (s/keys :req-un [::id ::user ::name ::collection-id]))

(def ^:private update-image-sql
  "update images
      set name = $3,
          collection_id = $2
    where id = $1
      and user_id = $4
   returning *;")

(sc/defmutation :update-image
  {:doc "Update a image entry."
   :spec ::update-image}
  [{:keys [id name user collection-id] :as params}]
  (let [sql update-image-sql]
    (db/query-one db/pool [sql id collection-id name user])))

;; --- Copy Image

(declare retrieve-image)

(s/def ::copy-image
  (s/keys :req-un [::id ::collection-id ::user]))

(sc/defmutation :copy-image
  {:doc "Copy image from one collection to an other."
   :spec ::copy-image}
  [{:keys [user id collection-id] :as params}]
  (letfn [(copy-image [conn {:keys [path] :as image}]
            (-> (ds/lookup media/images-storage (:path image))
                (p/then (fn [path] (ds/save media/images-storage (fs/name path) path)))
                (p/then (fn [path]
                          (-> image
                              (assoc :path (str path) :collection-id collection-id)
                              (dissoc :id))))
                (p/then (partial store-image-in-db conn))))]

    (db/with-atomic [conn db/pool]
      (-> (retrieve-image conn {:id id :user user})
          (p/then sc/raise-not-found-if-nil)
          (p/then (partial copy-image conn))))))

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

(sc/defmutation :delete-image
  {:doc "Delete image entry."
   :spec ::delete-image}
  [{:keys [user id] :as params}]
  (let [sql "update images
                set deleted_at = clock_timestamp()
              where id = $1
                and user_id = $2
             returning *"]
    (db/query-one db/pool [sql id user])))

;; --- Query Images by Collection (id)

(def images-by-collection-sql
  "select * from images
    where (user_id = $1 or
           user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and deleted_at is null
      and case when $2::uuid is null then collection_id is null
               else collection_id = $2::uuid
          end
   order by created_at desc;")

(s/def ::images-by-collection-query
  (s/keys :req-un [::user]
          :opt-un [::collection-id]))

(sc/defquery :images-by-collection
  {:doc "Get all images of a collection"
   :spec ::images-by-collection-query}
  [{:keys [user collection-id] :as params}]
  (let [sqlv [images-by-collection-sql user collection-id]]
    (-> (db/query db/pool sqlv)
        (p/then populate-thumbnails)
        (p/then #(mapv populate-urls %)))))

