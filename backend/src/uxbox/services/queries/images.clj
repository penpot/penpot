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
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.data :as data]
   [uxbox.util.uuid :as uuid]
   [vertx.core :as vc]))

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::user ::us/uuid)
(s/def ::collection-id (s/nilable ::us/uuid))

;; --- Query: Images Collections

(def ^:private sql:collections
  "select *,
          (select count(*) from images where collection_id = ic.id) as num_images
     from image_collections as ic
    where (ic.user_id = $1 or
           ic.user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and ic.deleted_at is null
    order by ic.created_at desc;")

(s/def ::images-collections
  (s/keys :req-un [::user]))

(sq/defquery ::images-collections
  [{:keys [user] :as params}]
  (db/query db/pool [sql:collections user]))


;; --- Query: Image by ID

(defn retrieve-image
  [conn id]
  (let [sql "select * from images
              where id = $1
                and deleted_at is null;"]
    (db/query-one conn [sql id])))

(s/def ::id ::us/uuid)
(s/def ::image-by-id
  (s/keys :req-un [::user ::id]))

(sq/defquery ::image-by-id
  [params]
  (-> (retrieve-image db/pool (:id params))
      (p/then' #(images/resolve-urls % :path :uri))
      (p/then' #(images/resolve-urls % :thumb-path :thumb-uri))))

;; --- Query: Images by collection ID

(def sql:images-by-collection
  "select * from images
    where (user_id = $1 or
           user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and deleted_at is null
   order by created_at desc")

(def sql:images-by-collection
  (str "with images as (" sql:images-by-collection ")
        select im.* from images as im
         where im.collection_id = $2"))

(s/def ::images-by-collection
  (s/keys :req-un [::user]
          :opt-un [::collection-id]))

;; TODO: check if we can resolve url with transducer for reduce
;; garbage generation for each request

(sq/defquery ::images-by-collection
  [{:keys [user collection-id] :as params}]
  (let [sqlv [sql:images-by-collection user collection-id]]
    (-> (db/query db/pool sqlv)
        (p/then' (fn [rows]
                   (->> rows
                        (mapv #(images/resolve-urls % :path :uri))
                        (mapv #(images/resolve-urls % :thumb-path :thumb-uri))))))))
