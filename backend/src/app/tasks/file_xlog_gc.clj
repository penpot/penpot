;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-xlog-gc
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [integrant.core :as ig]))

;; Get the latest available snapshots without exceeding the total
;; snapshot limit
(def ^:private sql:get-latest-snapshots
  "SELECT fch.id, fch.created_at
     FROM file_change AS fch
    WHERE fch.file_id = ?
      AND fch.created_by = 'system'
      AND fch.data IS NOT NULL
      AND fch.deleted_at > now()
    ORDER BY fch.created_at DESC
    LIMIT ?")

;; Mark all snapshots that are outside the allowed total threshold
;; available for the GC
(def ^:private sql:delete-snapshots
  "UPDATE file_change
      SET deleted_at = now()
    WHERE file_id = ?
      AND deleted_at > now()
      AND data IS NOT NULL
      AND created_by = 'system'
      AND created_at < ?")

(defn- get-alive-snapshots
  [conn file-id]
  (let [total     (cf/get :auto-file-snapshot-total 10)
        snapshots (db/exec! conn [sql:get-latest-snapshots file-id total])]
    (not-empty snapshots)))

(defn- delete-old-snapshots!
  [{:keys [::db/conn] :as cfg} file-id]
  (when-let [snapshots (get-alive-snapshots conn file-id)]
    (let [last-date (-> snapshots peek :created-at)
          result    (db/exec-one! conn [sql:delete-snapshots file-id last-date])]
      (l/inf :hint "delete old file snapshots"
             :file-id (str file-id)
             :current (count snapshots)
             :deleted (db/get-update-count result)))))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (let [file-id (:file-id props)]
      (assert (uuid? file-id) "expected file-id on props")
      (-> cfg
          (assoc ::db/rollback (:rollback props false))
          (db/tx-run! delete-old-snapshots! file-id)))))
