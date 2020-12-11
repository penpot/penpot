;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.file-xlog-gc
  "A maintenance task that performs a garbage collection of the file
  change (transaction) log."
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]))

(def max-age (dt/duration {:hours 12}))

(def sql:delete-files-xlog
  "delete from task_completed
    where scheduled_at < now() - ?::interval")

(defn handler
  [{:keys [props] :as task}]
  (db/with-atomic [conn db/pool]
    (let [interval (db/interval max-age)
          result   (db/exec-one! conn [sql:delete-files-xlog interval])]
      (log/infof "removed %s rows from file_changes table." (:next.jdbc/update-count result))
      nil)))

(mtx/instrument-with-summary!
 {:var #'handler
  :id "tasks__file_xlog_gc"
  :help "Timing of task: file_xlog_gc"})
