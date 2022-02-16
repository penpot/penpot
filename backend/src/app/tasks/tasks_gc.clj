;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tasks.tasks-gc
  "A maintenance task that performs a cleanup of already executed tasks
  from the database table."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare sql:delete-completed-tasks)

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::max-age]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool max-age] :as cfg}]
  (fn [_]
    (db/with-atomic [conn pool]
      (let [interval (db/interval max-age)
            result   (db/exec-one! conn [sql:delete-completed-tasks interval])
            result   (:next.jdbc/update-count result)]
        (l/debug :hint "trim completed tasks table" :removed result)
        result))))

(def ^:private
  sql:delete-completed-tasks
  "delete from task_completed
    where scheduled_at < now() - ?::interval")
