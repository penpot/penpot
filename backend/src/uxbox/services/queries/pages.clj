;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.pages
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.util.blob :as blob]
   [uxbox.util.spec :as us]
   [uxbox.util.sql :as sql]))

;; --- Helpers & Specs

(declare decode-row)

(s/def ::id ::us/uuid)
(s/def ::user ::us/uuid)
(s/def ::project-id ::us/uuid)

;; --- Query: Pages by Project

(s/def ::pages-by-project
  (s/keys :req-un [::user ::project-id]))

(sq/defquery ::pages-by-project
  [{:keys [user project-id] :as params}]
  (let [sql "select pg.*,
                    pg.data,
                    pg.metadata
               from pages as pg
              where pg.user_id = $2
                and pg.project_id = $1
                and pg.deleted_at is null
              order by pg.created_at asc;"]
    (-> (db/query db/pool [sql project-id user])
        (p/then #(mapv decode-row %)))))

;; --- Query: Page by Id

(s/def ::page
  (s/keys :req-un [::user ::id]))

(sq/defquery ::page
  [{:keys [user id] :as params}]
  (let [sql "select pg.*,
                    pg.data,
                    pg.metadata
               from pages as pg
              where pg.user_id = $2
                and pg.id = $1
                and pg.deleted_at is null"]
    (-> (db/query-one db/pool [sql id user])
        (p/then' decode-row))))

;; --- Query: Page History

(s/def ::page-id ::us/uuid)
(s/def ::max ::us/integer)
(s/def ::pinned ::us/boolean)
(s/def ::since ::us/integer)

(s/def ::page-history
  (s/keys :req-un [::page-id ::user]
          :opt-un [::max ::pinned ::since]))

(sq/defquery ::page-history
  [{:keys [page-id user since max pinned] :or {since Long/MAX_VALUE max 10}}]
  (let [sql (-> (sql/from ["pages_history" "ph"])
                (sql/select "ph.*")
                (sql/where ["ph.user_id = ?" user]
                           ["ph.page_id = ?" page-id]
                           ["ph.version < ?" since]
                           (when pinned
                             ["ph.pinned = ?" true]))
                (sql/order "ph.version desc")
                (sql/limit max))]
    (-> (db/query db/pool (sql/fmt sql))
        (p/then (partial mapv decode-row)))))

;; --- Helpers

(defn decode-row
  [{:keys [data metadata] :as row}]
  (when row
    (cond-> row
      data (assoc :data (blob/decode data))
      metadata (assoc :metadata (blob/decode metadata)))))
