;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.clean-tasks-table
  "A maintenance task that performs a cleanup of already executed tasks
  from the database table."
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]))

(def max-age (dt/duration {:hours 24}))

(def sql:delete-completed-tasks
  "delete from task_completed
    where scheduled_at < now() - ?::interval")

(defn handler
  [task]
  (db/with-atomic [conn db/pool]
    (let [interval (db/interval max-age)
          result   (db/exec-one! conn [sql:delete-completed-tasks interval])]
      (log/infof "removed %s rows from tasks_completed table." (:next.jdbc/update-count result))
      nil)))

(mtx/instrument-with-summary!
 {:var #'handler
  :id "tasks__clean_tasks_table"
  :help "Timing of task: clean_task_table"})


