;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.maintenance
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Delete Executed Tasks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This tasks perform a cleanup of already executed tasks from the
;; database.

(s/def ::max-age ::dt/duration)
(s/def ::delete-completed-tasks
  (s/keys :req-un [::max-age]))

(def sql:delete-completed-tasks
  "delete from task_completed
    where scheduled_at < now() - ?::interval")

(defn delete-executed-tasks
  [{:keys [props] :as task}]
  (us/verify ::delete-completed-tasks props)
  (db/with-atomic [conn db/pool]
    (let [max-age (:max-age props)
          result  (db/exec-one! conn [sql:delete-completed-tasks (db/interval max-age)])]
      (log/infof "Removed %s rows from tasks_completed table." (:next.jdbc/update-count result))
      nil)))

(mtx/instrument-with-summary!
 {:var #'delete-executed-tasks
  :id "tasks__maintenance__delete_executed_tasks"
  :help "Timing of mainentance task function: delete-remove-tasks."})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Delete old files xlog
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::delete-old-file-xlog
  (s/keys :req-un [::max-age]))

(def sql:delete-files-xlog
  "delete from task_completed
    where scheduled_at < now() - ?::interval")

(defn delete-old-files-xlog
  [{:keys [props] :as task}]
  (db/with-atomic [conn db/pool]
    (let [max-age (:max-age props)
          result  (db/exec-one! conn [sql:delete-files-xlog (db/interval max-age)])]
      (log/infof "Removed %s rows from file_changes table." (:next.jdbc/update-count result))
      nil)))

(mtx/instrument-with-summary!
 {:var #'delete-old-files-xlog
  :id "tasks__maintenance__delete_old_files_xlog"
  :help "Timing of mainentance task function: delete-old-files-xlog."})
