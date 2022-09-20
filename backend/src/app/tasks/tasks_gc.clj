;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.tasks-gc
  "A maintenance task that performs a cleanup of already executed tasks
  from the database table."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare sql:delete-completed-tasks)

(s/def ::min-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]
          :opt-un [::min-age]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (merge {:min-age cf/deletion-delay}
         (d/without-nils cfg)))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [params]
    (let [min-age (or (:min-age params) (:min-age cfg))]
      (db/with-atomic [conn pool]
        (let [interval (db/interval min-age)
              result   (db/exec-one! conn [sql:delete-completed-tasks interval])
              result   (:next.jdbc/update-count result)]
          (l/debug :hint "task finished" :total result)

          (when (:rollback? params)
            (db/rollback! conn))

          result)))))

(def ^:private
  sql:delete-completed-tasks
  "delete from task_completed
    where scheduled_at < now() - ?::interval")
