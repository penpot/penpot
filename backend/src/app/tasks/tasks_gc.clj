;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.tasks-gc
  "A maintenance task that performs a cleanup of already executed tasks
  from the database table."
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [integrant.core :as ig]))

(def ^:private
  sql:delete-completed-tasks
  "DELETE FROM task WHERE scheduled_at < now() - ?::interval")

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
