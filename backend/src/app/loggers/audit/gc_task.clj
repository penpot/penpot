;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.audit.gc-task
  (:require
   [app.common.logging :as l]
   [app.db :as db]
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

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "valid database pool expected"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_]
    (clean-archived! cfg)))
