;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.images
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
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

(defn populate-thumbnail
  [row]
  (let [opts +thumbnail-options+]
    (-> (px/submit! #(images/populate-thumbnails row opts))
        (su/handle-on-context))))

(defn populate-thumbnails
  [rows]
  (if (empty? rows)
    rows
    (p/all (map populate-thumbnail rows))))

(defn populate-urls
  [row]
  (images/populate-urls row media/images-storage :path :url))

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::user ::us/uuid)
(s/def ::collection-id (s/nilable ::us/uuid))

(def ^:private images-collections-sql
  "select *,
          (select count(*) from images where collection_id = ic.id) as num_images
     from images_collections as ic
    where (ic.user_id = $1 or
           ic.user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and ic.deleted_at is null
    order by ic.created_at desc;")

(s/def ::images-collections
  (s/keys :req-un [::user]))

(sq/defquery ::images-collections
  [{:keys [user] :as params}]
  (db/query db/pool [images-collections-sql user]))

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

(sq/defquery ::images-by-collection
  [{:keys [user collection-id] :as params}]
  (let [sqlv [images-by-collection-sql user collection-id]]
    (-> (db/query db/pool sqlv)
        (p/then populate-thumbnails)
        (p/then #(mapv populate-urls %)))))

