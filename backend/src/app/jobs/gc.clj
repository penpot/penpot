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
    completed/failed/cancelled) older than the `:jobs-retention` delay.
    The dormant legacy `task` table is cleaned by the parallel legacy
    version. User-facing terminal rows (profile_id NOT NULL) are
    NOT swept by retention: they are governed by `expires_at` (or stay
    as ledger history).

  Rows carrying a `resource_id` touch their storage object (touched_at
  set in the same transaction, mirroring `sto/touch-object!`) so
  `storage-gc-touched` reclaims it: deleting the job row does not touch
  the `storage_object` (ON DELETE SET NULL only fires when the object
  row is deleted, not the job row), and gc-touched only analyzes objects
  already marked with `touched_at` — without the explicit touch the
  object would be orphaned.

  Each batch deletes and touches in its own transaction: a crash
  leaves the remaining batches for the next tick, and an object can
  never lose its referencing row without being marked for reclaim."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.metrics :as jobs-metrics]
   [app.metrics :as-alias mtx]
   [integrant.core :as ig]))

(def ^:private sql:touch-objects
  "UPDATE storage_object
      SET touched_at = ?
    WHERE id = ANY(?::uuid[])")

(def ^:private gc-batch-size 1000)

(def ^:private sql:delete-expired-jobs
  "DELETE FROM job
     WHERE id IN (SELECT id
                    FROM job
                   WHERE expires_at < now()
                     AND status NOT IN ('running', 'retry')
                   LIMIT ?)
   RETURNING resource_id")

(def ^:private sql:delete-retained-jobs
  "DELETE FROM job
     WHERE id IN (SELECT id
                    FROM job
                   WHERE status IN ('completed', 'failed', 'cancelled')
                     AND profile_id IS NULL
                     AND modified_at < now() - ?::interval
                   LIMIT ?)
   RETURNING resource_id")

(defn- touch-resources
  [conn resource-ids]
  (if (seq resource-ids)
    (-> (db/exec-one! conn [sql:touch-objects (ct/now)
                            (db/create-array conn "uuid" resource-ids)])
        (db/get-update-count))
    0))

(defn- delete-jobs
  [cfg kind sql & params]
  (loop [deleted 0
         touched 0]
    (let [[rows touched-now]
          (db/tx-run! cfg
                      (fn [{:keys [::db/conn]}]
                        (let [rows         (db/exec! conn (conj (into [sql] params)
                                                                gc-batch-size))
                              resource-ids (into [] (keep :resource-id) rows)
                              touched      (touch-resources conn resource-ids)]
                          (db/after-commit!
                           #(do
                              (jobs-metrics/record-gc-rows
                               (::mtx/metrics cfg) kind :deleted (count rows))
                              (jobs-metrics/record-gc-rows
                               (::mtx/metrics cfg) kind :touched touched)))
                          [rows touched])))
          deleted' (+ deleted (count rows))
          touched' (+ touched touched-now)]
      (if (< (count rows) gc-batch-size)
        [deleted' touched']
        (do (jobs/heartbeat cfg)
            (recur deleted' touched'))))))

(declare execute-jobs-gc)

(def schema:jobs-gc-params
  "min-age: duration object, integer millis or duration string
  in-process; integer millis or duration string over the job pipeline
  (a duration object does not survive JSON encoding). The handler
  always normalizes with ct/duration."
  [:map
   [:min-age {:optional true} [:or :int :string ::ct/duration]]])

(defmethod ig/init-key ::jobs-gc-job-def
  [_ cfg]
  {::jobs/name      :jobs-gc
   ::jobs/schema    schema:jobs-gc-params
   ::jobs/handler   (partial execute-jobs-gc cfg)
   ::jobs/decoder   (sm/decoder schema:jobs-gc-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:jobs-gc-params)})

(defn execute-jobs-gc
  "Plain job handler: delete expired rows (expires_at) and retained
  internal terminal rows, marking the storage resources of the deleted
  rows as touched.

  Deletes run in bounded batches of 1000 rows, each batch in its own
  transaction, with a heartbeat between batches so long sweeps neither
  spike the WAL nor outrun the job lease."
  [cfg params]
  (let [min-age (ct/duration (or (:min-age params)
                                 (cf/get-jobs-retention)))
        expired-tpoint (ct/tpoint)
        [deleted-expired touched-expired]
        (delete-jobs cfg :expired sql:delete-expired-jobs)
        retained-tpoint (ct/tpoint)
        [deleted-retained touched-retained]
        (delete-jobs cfg :retained sql:delete-retained-jobs
                     (db/interval min-age))]
    (db/after-commit!
     #(jobs-metrics/record-gc-duration
       (::mtx/metrics cfg) :expired (inst-ms (expired-tpoint))))
    (db/after-commit!
     #(jobs-metrics/record-gc-duration
       (::mtx/metrics cfg) :retained (inst-ms (retained-tpoint))))
    (l/dbg :hint "jobs gc finished"
           :deleted-expired deleted-expired
           :touched-expired touched-expired
           :deleted-retained deleted-retained
           :touched-retained touched-retained)
    {:deleted-expired    deleted-expired
     :touched-expired    touched-expired
     :deleted-retained   deleted-retained
     :touched-retained   touched-retained}))

