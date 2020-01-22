;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.icons
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.util.blob :as blob]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::user ::us/uuid)
(s/def ::collection-id (s/nilable ::us/uuid))

(defn decode-icon-row
  [{:keys [metadata] :as row}]
  (when row
    (cond-> row
      metadata (assoc :metadata (blob/decode metadata)))))

;; --- Query: Collections

(def sql:icons-collections
  "select *,
          (select count(*) from icons where collection_id = ic.id) as num_icons
     from icon_collections as ic
    where (ic.user_id = $1 or
           ic.user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and ic.deleted_at is null
    order by ic.created_at desc")

(s/def ::icons-collections
  (s/keys :req-un [::user]))

(sq/defquery ::icons-collections
  [{:keys [user] :as params}]
  (let [sqlv [sql:icons-collections user]]
    (db/query db/pool sqlv)))

;; --- Icons By Collection ID

(def ^:private icons-by-collection-sql
  "select *
     from icons as i
    where (i.user_id = $1 or
           i.user_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and i.deleted_at is null
      and case when $2::uuid is null then i.collection_id is null
               else i.collection_id = $2::uuid
          end
    order by i.created_at desc")

(s/def ::icons-by-collection
  (s/keys :req-un [::user]
          :opt-un [::collection-id]))

(sq/defquery ::icons-by-collection
  [{:keys [user collection-id] :as params}]
  (let [sqlv [icons-by-collection-sql user collection-id]]
    (-> (db/query db/pool sqlv)
        (p/then' #(mapv decode-icon-row %)))))
