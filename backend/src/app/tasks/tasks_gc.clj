;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.tasks-gc
  "A maintenance task that performs a cleanup of already executed tasks
  from the database table."
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(declare handler)

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::mtx/metrics ::max-age]))

(defmethod ig/init-key ::handler
  [_ {:keys [metrics] :as cfg}]
  (let [handler #(handler cfg %)]
    (->> {:registry (:registry metrics)
          :type :summary
          :name "task_tasks_gc_timing"
          :help "tasks garbage collection task timing"}
         (mtx/instrument handler))))

(def ^:private
  sql:delete-completed-tasks
  "delete from task_completed
    where scheduled_at < now() - ?::interval")

(defn- handler
  [{:keys [pool max-age]} _]
  (db/with-atomic [conn pool]
    (let [interval (db/interval max-age)
          result   (db/exec-one! conn [sql:delete-completed-tasks interval])
          result   (:next.jdbc/update-count result)]
      (log/infof "removed %s rows from tasks_completed table" result)
      nil)))

