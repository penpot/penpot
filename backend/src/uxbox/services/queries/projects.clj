;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.projects
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.util.blob :as blob]
   [uxbox.util.spec :as us]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::token ::us/string)
(s/def ::user ::us/uuid)

;; --- Query: Projects

(def ^:private projects-sql
  "select distinct on (p.id, p.created_at)
          p.*,
          array_agg(pg.id) over (
            partition by p.id
            order by pg.created_at
            range between unbounded preceding and unbounded following
          ) as pages
    from projects as p
    left join pages as pg
           on (pg.project_id = p.id)
   where p.user_id = $1
   order by p.created_at asc")

(s/def ::projects
  (s/keys :req-un [::user]))

(sq/defquery ::projects
  [{:keys [user] :as params}]
  (-> (db/query db/pool [projects-sql user])
      (p/then (fn [rows]
                (mapv #(update % :pages vec) rows)))))
