;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.orphan-teams-gc
  "A maintenance task that performs orphan teams GC."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare ^:private delete-orphan-teams!)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [params]
    (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                      (l/inf :hint "gc started" :rollback? (boolean (:rollback? params)))
                      (let [total (delete-orphan-teams! cfg)]
                        (l/inf :hint "task finished"
                               :teams total
                               :rollback? (boolean (:rollback? params)))

                        (when (:rollback? params)
                          (db/rollback! conn))

                        {:processed total})))))

(def ^:private sql:get-orphan-teams
  "SELECT t.id
     FROM team AS t
     LEFT JOIN team_profile_rel AS tpr
            ON (t.id = tpr.team_id)
    WHERE tpr.profile_id IS NULL
      AND t.deleted_at IS NULL
    ORDER BY t.created_at ASC
      FOR UPDATE OF t
     SKIP LOCKED")

(defn- delete-orphan-teams!
  "Find all orphan teams (with no members) and mark them for
  deletion (soft delete)."
  [{:keys [::db/conn] :as cfg}]
  (->> (db/cursor conn sql:get-orphan-teams)
       (map :id)
       (reduce (fn [total team-id]
                 (l/trc :hint "mark orphan team for deletion" :id (str team-id))
                 (db/update! conn :team
                             {:deleted-at (dt/now)}
                             {:id team-id})
                 (inc total))
               0)))
