;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.gc-task
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.jobs :as jobs]
   [integrant.core :as ig]))

(def ^:private sql:clean-archived
  "DELETE FROM audit_log
    WHERE archived_at IS NOT NULL")

(defn- clean-archived!
  [{:keys [::db/pool]}]
  (let [result (db/exec-one! pool [sql:clean-archived])
        result (db/get-update-count result)]
    (l/debug :hint "delete archived audit log entries" :deleted result)
    result))

(declare execute-audit-log-gc!)

(def schema:audit-log-gc-params
  "Params map (no params needed; config-derived only)."
  [:map {:closed true}])

(defmethod ig/init-key ::audit-log-gc-job-def
  [_ cfg]
  {::jobs/name      :audit-log-gc
   ::jobs/schema    schema:audit-log-gc-params
   ::jobs/handler   (partial execute-audit-log-gc! cfg)
   ::jobs/decoder   (sm/decoder schema:audit-log-gc-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-gc-params)})

(defn execute-audit-log-gc!
  "Plain job handler: delete the archived audit log entries."
  ([cfg] (execute-audit-log-gc! cfg nil))
  ([cfg _params]
   (clean-archived! cfg)))
