;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.file-fragments-gc
  "Prepare fragment marks from one database snapshot, then verify the
  roots again under the file lock before applying a bounded lot."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-change-refs :as refs]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [app.storage :as sto]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(def ^:private max-fragments-per-marking-transaction 100)
(def ^:private max-fragment-candidates-per-run 1000)
(def ^:private busy-or-stale-retry-delay (ct/duration {:seconds 30}))
(def ^:private incomplete-index-retry-delay (ct/duration "1m"))

(def ^:private sql:get-live-file-gc-state
  "Read the live file's revision and inactivity timestamp. Compare xmin
  (the transaction that created the row version) and ctid (its physical
  location) as a pair: revn can stay the same after a row update, and
  updates within one transaction can share xmin."
  "SELECT revn, modified_at, xmin::text AS file_xid, ctid::text AS file_tid
     FROM file WHERE id = ? AND deleted_at IS NULL")

(def ^:private sql:lock-file
  "Lock the file row before revalidating and marking a lot. Writers use
  the same lock; skip a busy file rather than waiting for it."
  (str sql:get-live-file-gc-state " FOR UPDATE SKIP LOCKED"))

(def ^:private sql:get-main-file-data
  "Read the live canonical main and its row version together. Decode the
  current fragment references during preparation, outside the file lock."
  "SELECT id, file_id, backend, storage_object_id, data,
          xmin::text AS main_xid, ctid::text AS main_tid
     FROM file_data
    WHERE file_id = ? AND id = file_id AND type = 'main' AND deleted_at IS NULL")

(def ^:private sql:get-main-file-data-version
  "Check the main's row version under the file lock without fetching its
  payload. A rewrite can change references without advancing revn."
  "SELECT xmin::text AS main_xid, ctid::text AS main_tid
     FROM file_data
    WHERE file_id = ? AND id = file_id AND type = 'main' AND deleted_at IS NULL")

(def ^:private sql:get-history-ids
  "Record the history IDs at the current revn in the same database
  snapshot as the coverage check and candidate selection. A new snapshot
  can add a history ID without changing either the file or the main row."
  "SELECT id FROM file_change WHERE file_id = ? AND revn = ?")

(def ^:private sql:history-changed
  "Detect history IDs added at the prepared revn, using a fresh statement
  after taking the file lock. Purged IDs do not invalidate the lot: they
  can only remove references that kept fragments alive."
  "SELECT EXISTS (
     SELECT 1 FROM file_change WHERE file_id = ? AND revn = ?
       AND id <> ALL(?::uuid[])
   ) AS changed")

(def ^:private sql:get-unreferenced-fragment-ids
  "Find a bounded set of live fragments referenced by neither the main
  nor the indexed history. Run only after proving index coverage in the
  same database snapshot: a missing index row is not an empty ref set."
  "WITH used AS MATERIALIZED (
     SELECT DISTINCT unnest(fragment_ids) AS id
       FROM file_change_refs WHERE file_id = ?
   )
   SELECT d.id FROM file_data d
    WHERE d.file_id = ? AND d.type = 'fragment' AND d.deleted_at IS NULL
      AND d.id <> ALL(?::uuid[])
      AND NOT EXISTS (SELECT 1 FROM used WHERE used.id = d.id)
    ORDER BY d.id LIMIT ?")

(def ^:private sql:mark-fragments
  "Set deleted_at only on still-live fragments in the revalidated lot.
  Return the rows actually marked; objects GC handles physical deletion."
  "UPDATE file_data SET deleted_at = ?
    WHERE file_id = ? AND id = ANY(?::uuid[])
      AND type = 'fragment' AND deleted_at IS NULL
    RETURNING id")

(defn- get-live-file-gc-state
  [cfg file-id]
  (db/exec-one! cfg [sql:get-live-file-gc-state file-id]))

(defn- get-and-lock-file-state
  [{:keys [::db/conn]} file-id]
  (db/exec-one! conn [sql:lock-file file-id]))

(defn- get-deadline
  [{:keys [modified-at]}]
  (ct/plus modified-at (cf/get-file-clean-delay)))

(defn- get-file-row-version
  [file]
  (select-keys file [:revn :file-xid :file-tid]))

(defn- get-main-row-version
  [main]
  (select-keys main [:main-xid :main-tid]))

(defn- get-main-fragment-ids
  [cfg main]
  (some->> main
           (fdata/resolve-file-data cfg)
           (fdata/decode-file-data cfg)
           :data
           (fdata/get-used-pointer-ids)))

(defn- prepare-lot
  [{:keys [::db/conn] :as cfg} file-id]
  ;; A fresh view per query could include a new snapshot ID
  ;; in history-ids even though the coverage check missed it.
  (db/set-tx-isolation cfg :repeatable-read)

  (if-let [file (get-live-file-gc-state cfg file-id)]
    (let [deadline (get-deadline file)]
      (cond
        ;; Defer GC until the file has been inactive long enough.
        (ct/is-after? deadline (ct/now))
        {:status :recent :deadline deadline :file-id file-id :marked 0}

        ;; Missing index rows may hide references. Do not select candidates yet.
        (seq (refs/get-missing-change-ids cfg file-id 1))
        {:status :incomplete :file-id file-id}

        ;; With full history coverage, check the main before selecting candidates.
        :else
        (if-let [main (db/exec-one! conn [sql:get-main-file-data file-id])]
          ;; A missing payload is not the same as an empty set of references.
          (let [pointer-ids  (or (get-main-fragment-ids cfg main)
                                 (ex/raise :type :internal
                                           :code :invalid-main-payload
                                           :hint "file main has no payload"
                                           :file-id file-id))
                fragment-ids (db/create-array conn "uuid" pointer-ids)
                ids          (->> (db/plan conn [sql:get-unreferenced-fragment-ids
                                                 file-id file-id fragment-ids max-fragment-candidates-per-run])
                                  (into [] d/xf:map-id))]
            (if (seq ids)
              ;; Keep the versions and history IDs for revalidation under the lock.
              {:status :prepared
               :file-id file-id
               :ids ids
               :file-version (get-file-row-version file)
               :main-version (get-main-row-version main)
               :history-ids  (->> (db/plan conn [sql:get-history-ids file-id (:revn file)])
                                  (into #{} d/xf:map-id))}
              ;; No unreferenced fragments are visible in this database snapshot.
              {:status :complete
               :file-id file-id
               :marked 0}))

          ;; A live file without an active main is inconsistent.
          (ex/raise :type :internal
                    :code :missing-main-file-data
                    :hint "live file has no live main row in file_data"
                    :file-id file-id))))

    ;; The file is missing or deleted; no follow-up is needed.
    {:status :gone :file-id file-id :marked 0}))

(defn- file-row-version-changed?
  [file {:keys [file-version]}]
  (not= file-version (get-file-row-version file)))

(defn- main-row-version-changed?
  [conn {:keys [file-id main-version]}]
  (not= main-version (db/exec-one! conn [sql:get-main-file-data-version file-id])))

(defn- history-has-new-ids?
  [conn {:keys [file-id file-version history-ids]}]
  ;; Use a fresh database snapshot after the locking SELECT.
  (:changed (db/exec-one! conn [sql:history-changed file-id (:revn file-version)
                                (db/create-array conn "uuid" history-ids)])))

(defn- mark-lot
  [{:keys [::db/conn] :as cfg} {:keys [ids file-id] :as lot}]
  ;; Revalidation must see commits that landed before the lock.
  (db/set-tx-isolation cfg :read-committed)
  ;; Bound the SQL work while the file row is locked, including
  ;; time behind a concurrent row purge.
  (db/exec-one! conn ["SET LOCAL statement_timeout = '1s'"])
  (db/exec-one! conn ["SET LOCAL lock_timeout = '1s'"])

  (if-let [file (get-and-lock-file-state cfg file-id)]
    (let [now      (ct/now)
          deadline (get-deadline file)]
      (cond
        (ct/is-after? deadline now)
        (-> lot
            (assoc :status :recent)
            (assoc :deadline deadline))

        (or (file-row-version-changed? file lot)
            (main-row-version-changed? conn lot)
            (history-has-new-ids? conn lot))
        (assoc lot :status :stale)

        :else
        (let [rows (db/exec! conn [sql:mark-fragments now file-id
                                   (db/create-array conn "uuid" ids)])]
          (-> lot
              (assoc :status :complete)
              (assoc :marked (count rows))))))

    (if (get-live-file-gc-state cfg file-id)
      (assoc lot :status :busy)
      (assoc lot :status :gone))))

(defn- mark-prepared
  [cfg {:keys [ids] :as lot}]
  ;; Reuse the same bounded preparation without rebuilding the history
  ;; query for every lot. Each lot still has to validate under a fresh lock.
  (reduce (fn [{:keys [marked]} ids]
            (let [result (-> (db/own-transaction cfg)
                             (db/tx-run! mark-lot (assoc lot :ids ids))
                             (select-keys [:status :deadline :marked :file-id]))]
              (if (= :complete (:status result))
                (update result :marked + marked)
                (reduced (assoc result :marked marked)))))
          {:status :complete :marked 0}
          (partition-all max-fragments-per-marking-transaction ids)))

(defn- schedule
  [cfg file-id delay]
  (jobs/submit cfg {::jobs/name :file-fragments-gc
                    ::jobs/params {:file-id file-id}
                    ::jobs/label (str file-id)
                    ::jobs/dedupe true
                    ::jobs/delay delay}))

(defn- fragment-candidate-limit-reached?
  [{:keys [ids]}]
  (= max-fragment-candidates-per-run (count ids)))

(defn execute-gc
  [cfg file-id]
  (let [lot (-> (db/own-transaction cfg)
                (assoc ::db/read-only true)
                (db/tx-run! prepare-lot file-id))]
    (loop [{:keys [status] :as result} lot]
      (cond
        (= :prepared status)
        (recur (mark-prepared cfg result))

        (= :incomplete status)
        (do
          (jobs/submit cfg {::jobs/name :index-file-change-refs-scheduler
                            ::jobs/params {:file-id file-id}
                            ::jobs/label (str file-id)
                            ::jobs/dedupe true})
          (ex/raise :type ::wrk/retry
                    :code :incomplete-file-change-refs
                    :hint "file change references are not fully indexed"
                    :file-id file-id
                    :delay incomplete-index-retry-delay))

        (= :recent status)
        (let [now   (ct/now)
              until (:deadline result)]
          (schedule cfg file-id
                    (if (ct/is-after? until now)
                      (ct/diff now until)
                      0))
          (dissoc result :deadline))

        (contains? #{:stale :busy} status)
        (do
          (schedule cfg file-id busy-or-stale-retry-delay)
          result)

        (= :complete status)
        (do
          (when (fragment-candidate-limit-reached? lot)
            (schedule cfg file-id 0))
          result)

        (= :gone status)
        result

        :else
        (ex/raise :type :internal
                  :code :unexpected-fragment-gc-status
                  :hint "unexpected fragment GC status"
                  :file-id file-id
                  :status status)))))

(def ^:private schema:params
  [:map {:closed true}
   [:file-id ::sm/uuid]])

(def ^:private schema:result
  [:map {:closed true}
   [:status [::sm/one-of #{:complete :recent :stale :busy :gone :disabled}]]
   [:file-id ::sm/uuid]
   [:marked [:int {:min 0}]]])

(defmethod ig/assert-key ::job-def
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage cfg)) "expected valid storage")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name          :file-fragments-gc
   ::jobs/schema        schema:params
   ::jobs/handler       (fn [_ {:keys [file-id]}]
                          ;; A queued job must not take a lock after pointer maps are disabled.
                          (if (contains? cf/flags :feature-fdata-pointer-map)
                            (execute-gc cfg file-id)
                            {:status :disabled :file-id file-id :marked 0}))
   ::jobs/decoder       (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator schema:params)
   ::jobs/result-schema schema:result})
