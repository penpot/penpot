;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.projects
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]))

(declare decode-row)

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::token ::us/string)
(s/def ::user ::us/uuid)


;; --- Query: Projects

(def sql:projects
  "select p.*
     from project_users as pu
    inner join projects as p on (p.id = pu.project_id)
    where pu.can_edit = true
      and pu.user_id = $1
    order by p.created_at asc")

(s/def ::projects
  (s/keys :req-un [::user]))

(sq/defquery ::projects
  [{:keys [user] :as params}]
  (-> (db/query db/pool [sql:projects user])
      (p/then' (partial mapv decode-row))))


;; --- Helpers

(defn decode-row
  [{:keys [metadata] :as row}]
  (when row
    (cond-> row
      metadata (assoc :metadata (blob/decode metadata)))))
