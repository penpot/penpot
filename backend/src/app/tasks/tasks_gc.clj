;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.tasks-gc
  "A maintenance task that performs a cleanup of already executed tasks
  from the database table."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [integrant.core :as ig]))

(def ^:private
  sql:delete-completed-tasks
  "DELETE FROM task WHERE scheduled_at < now() - ?::interval")

(def schema:tasks-gc-params
  "The min-age is a duration object when passed in-process and a text
  (json) when received over the job pipeline; decoded by ct/duration in
  the handler."
  [:map
   [:min-age {:optional true} :any]])

(declare execute-tasks-gc!)

(defmethod ig/init-key ::tasks-gc-job-def
  [_ cfg]
  {::jobs/name      :tasks-gc
   ::jobs/schema    schema:tasks-gc-params
   ::jobs/handler   (partial execute-tasks-gc! cfg)
   ::jobs/decoder   (sm/decoder schema:tasks-gc-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:tasks-gc-params)})

(defn execute-tasks-gc!
  "Plain job handler: delete terminal `task` rows (the legacy dormant
  table) older than the deletion delay."
  [cfg params]
  (let [min-age (or (:min-age params)
                    (cf/get-deletion-delay))]
    (-> (assoc cfg ::db/rollback (:rollback? params))
        (db/tx-run! (fn [{:keys [::db/conn]}]
                      (let [interval (db/interval min-age)
                            result   (db/exec-one! conn [sql:delete-completed-tasks interval])
                            result   (db/get-update-count result)]
                        (l/debug :hint "task finished" :total result)
                        result))))))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/expand-key ::handler
  [k v]
  {k (assoc v ::min-age (cf/get-deletion-delay))})

(defmethod ig/init-key ::handler
  [_ {:keys [::min-age] :as cfg}]
  (fn [{:keys [props] :as task}]
    (let [min-age (or (:min-age props) min-age)]
      (-> cfg
          (assoc ::db/rollback (:rollback? props))
          (db/tx-run! (fn [{:keys [::db/conn]}]
                        (let [interval (db/interval min-age)
                              result   (db/exec-one! conn [sql:delete-completed-tasks interval])
                              result   (db/get-update-count result)]
                          (l/debug :hint "task finished" :total result)
                          result)))))))
