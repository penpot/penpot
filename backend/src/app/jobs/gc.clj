;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.jobs.gc
  "A maintenance cron task for the unified `job` table:

  - expiration: deletes rows with `expires_at` in the past (any status,
    user or internal). Expiration is the mechanism for user-facing jobs
    and for jobs carrying volatile resources.
  - retention: deletes internal terminal rows (no profile, status in
    completed/failed/cancelled) older than the `:jobs-retention` delay;
    parity with the legacy tasks-gc, which keeps cleaning the dormant
    `task` table. User-facing terminal rows (profile_id NOT NULL) are
    NOT swept by retention: they are governed by `expires_at` (or stay
    as ledger history).

  Rows carrying a `resource_id` touch their storage object (touched_at
  set in the same transaction, mirroring `sto/touch-object!`) so
  `storage-gc-touched` reclaims it: deleting the job row does not touch
  the `storage_object` (ON DELETE SET NULL only fires when the object
  row is deleted, not the job row), and gc-touched only analyzes objects
  already marked with `touched_at` — without the explicit touch the
  object would be orphaned.

  The touch and the delete statements run in one transaction: a crash
  rolls everything back, so an object can never lose its referencing
  row without being marked for reclaim."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [integrant.core :as ig]))

(def ^:private sql:touch-objects
  "UPDATE storage_object
      SET touched_at = ?
    WHERE id = ANY(?::uuid[])")

(def ^:private sql:delete-expired-jobs
  "DELETE FROM job
     WHERE expires_at < now()
   RETURNING resource_id")

(def ^:private sql:delete-retained-jobs
  "DELETE FROM job
     WHERE status IN ('completed', 'failed', 'cancelled')
       AND profile_id IS NULL
       AND modified_at < now() - ?::interval
   RETURNING resource_id")

(defn- touch-resources!
  [conn resource-ids]
  (if (seq resource-ids)
    (-> (db/exec-one! conn [sql:touch-objects (ct/now)
                            (db/create-array conn "uuid" resource-ids)])
        (db/get-update-count))
    0))

(defn- delete-jobs!
  [conn sql & params]
  (let [rows         (db/exec! conn (into [sql] params))
        resource-ids (into [] (keep :resource-id) rows)]
    [(count rows) (touch-resources! conn resource-ids)]))

(declare execute-jobs-gc!)

(def schema:jobs-gc-params
  "min-age: duration object, integer millis or pg-interval text
  in-process; integer millis or text over the job pipeline (a duration
  object does not survive JSON encoding)."
  [:map
   [:min-age {:optional true} [:or :int :string ::ct/duration]]])

(defmethod ig/init-key ::jobs-gc-job-def
  [_ cfg]
  {::jobs/name      :jobs-gc
   ::jobs/schema    schema:jobs-gc-params
   ::jobs/handler   (partial execute-jobs-gc! cfg)
   ::jobs/decoder   (sm/decoder schema:jobs-gc-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:jobs-gc-params)})

(defn execute-jobs-gc!
  "Plain job handler: delete expired rows (expires_at) and retained
  internal terminal rows, marking the storage resources of the deleted
  rows as touched (same transaction).

  The `:rollback?` param (default false) forces the transaction to rollback
  instead of commit. Used for testing transactional code without side effects."
  [cfg params]
  (let [min-age (or (:min-age params)
                    (cf/get-jobs-retention))
        ;; Normalize to a duration, keeping pg-interval text as-is
        ;; (understood by db/interval, but not by ct/duration).
        min-age (if (string? min-age) min-age (ct/duration min-age))]
    (db/tx-run! (assoc cfg ::db/rollback (:rollback? params))
                (fn [{:keys [::db/conn]}]
                  (let [[deleted-expired touched-expired]
                        (delete-jobs! conn sql:delete-expired-jobs)

                        [deleted-retained touched-retained]
                        (delete-jobs! conn sql:delete-retained-jobs
                                      (db/interval min-age))]
                    (l/dbg :hint "jobs gc finished"
                           :deleted-expired deleted-expired
                           :touched-expired touched-expired
                           :deleted-retained deleted-retained
                           :touched-retained touched-retained)
                    {:deleted-expired    deleted-expired
                     :touched-expired    touched-expired
                     :deleted-retained   deleted-retained
                     :touched-retained   touched-retained})))))

