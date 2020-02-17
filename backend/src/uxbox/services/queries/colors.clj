;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.colors
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

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::collection-id (s/nilable ::us/uuid))

(defn decode-row
  [{:keys [metadata] :as row}]
  (when row
    (cond-> row
      metadata (assoc :metadata (blob/decode metadata)))))



;; --- Query: Collections

(def ^:private sql:collections
  "select *,
          (select count(*) from color where collection_id = ic.id) as num_colors
     from color_collection as ic
    where (ic.profile_id = $1 or
           ic.profile_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and ic.deleted_at is null
    order by ic.created_at desc")

(s/def ::color-collections
  (s/keys :req-un [::profile-id]))

(sq/defquery ::color-collections
  [{:keys [profile-id] :as params}]
  (let [sqlv [sql:collections profile-id]]
    (db/query db/pool sqlv)))



;; --- Colors By Collection ID

(def ^:private sql:colors
  "select *
     from color as i
    where (i.profile_id = $1 or
           i.profile_id = '00000000-0000-0000-0000-000000000000'::uuid)
      and i.deleted_at is null
      and i.collection_id = $2
    order by i.created_at desc")

(s/def ::colors
  (s/keys :req-un [::profile-id ::collection-id]))

(sq/defquery ::colors
  [{:keys [profile-id collection-id] :as params}]
  (-> (db/query db/pool [sql:colors profile-id collection-id])
      (p/then' #(mapv decode-row %))))



;; --- Query: Color (by ID)

(declare retrieve-color)

(s/def ::id ::us/uuid)
(s/def ::color
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::color
  [{:keys [id] :as params}]
  (-> (retrieve-color db/pool id)
      (p/then' su/raise-not-found-if-nil)))

(defn retrieve-color
  [conn id]
  (let [sql "select * from color
              where id = $1
                and deleted_at is null;"]
    (-> (db/query-one conn [sql id])
        (p/then' su/raise-not-found-if-nil))))
