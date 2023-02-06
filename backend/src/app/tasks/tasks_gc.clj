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
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(def ^:private
  sql:delete-completed-tasks
  "delete from task_completed
    where scheduled_at < now() - ?::interval")

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (assoc cfg ::min-age cf/deletion-delay))

(defmethod ig/init-key ::handler
  [_ {:keys [::db/pool ::min-age] :as cfg}]
  (fn [params]
    (let [min-age (or (:min-age params) min-age)]
      (db/with-atomic [conn pool]
        (let [interval (db/interval min-age)
              result   (db/exec-one! conn [sql:delete-completed-tasks interval])
              result   (db/get-update-count result)]

          (l/debug :hint "task finished" :total result)

          (when (:rollback? params)
            (db/rollback! conn))

          result)))))

