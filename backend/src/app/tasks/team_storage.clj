;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.team-storage
  "Background recompute of the cached per-team media storage usage
  (`team_storage_usage`) that the media-storage-bytes-per-team quota
  reads instead of scanning `storage_object` on every upload.

  The audit-log storage-usage consumer marks dirty the teams whose
  media may have changed. Every few minutes the `:schedule-team-storage`
  scheduler streams all dirty teams oldest-first and enqueues one
  `:recompute-team-storage` job per team (skipped while one is already live),
  so a failure is contained in its own job and the next tick
  reschedules it. Teams without a row are born on first quota check
  (fail open + dirty), never swept."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [app.rpc.quotes :as quotes]
   [integrant.core :as ig]))

(def ^:private sql:get-dirty-teams
  "Dirty teams first, then the ones nobody refreshed for a week. The
  second branch is the self-healing path: a mark lost on the way (an
  audit insert that failed, a flag that was off) would otherwise keep
  the counter low for ever. The table holds one row per team, so the
  stale branch needs no index."
  "SELECT team_id
     FROM team_storage_usage
    WHERE dirty_at IS NOT NULL
       OR updated_at < now() - interval '7 days'
    ORDER BY dirty_at ASC NULLS LAST, updated_at ASC")

(def ^:private sql:upsert-team-storage-usage
  "INSERT INTO team_storage_usage AS tsu (team_id, total_bytes, dirty_at, updated_at)
   SELECT ?, ?, NULL, now()
    WHERE EXISTS (SELECT 1 FROM team AS t WHERE t.id = ?)
   ON CONFLICT (team_id) DO UPDATE
      SET total_bytes = EXCLUDED.total_bytes,
          dirty_at = CASE WHEN tsu.dirty_at > ?::timestamptz THEN tsu.dirty_at ELSE NULL END,
          updated_at = now()")

(defn- recompute-team-storage
  "Recompute one team: slow read, fast write. A mark written by a
  transaction that started after this recompute began survives (its
  dirty_at is newer than the read) and the next tick recomputes it. A
  marker transaction that overlaps this one can still be cleared
  stale: the CASE is a cheap safety net, not a proof. A stale read can
  over-allow for one window (accepted: abuse prevention, not a hard
  limit)."
  [{:keys [::db/conn]} team-id]
  ;; Read the clock before the slow SUM: now() is stable per
  ;; transaction, and this whole worker runs in one, so a mark newer
  ;; than the read outlives the upsert.
  (let [read-at (:now (db/exec-one! conn ["SELECT now() AS now"]))
        total   (quotes/get-live-media-storage-bytes conn team-id)]
    (db/exec-one! conn [sql:upsert-team-storage-usage team-id total team-id read-at])
    total))

(defn execute-recompute-team-storage
  "Plain job handler: recompute a single team. A failure fails this
  job (the runner retries it); the team stays dirty, so the next
  scheduler tick enqueues it again."
  [cfg {:keys [team-id]}]
  (let [bytes (recompute-team-storage cfg team-id)]
    (l/trc :hint "team storage recomputed"
           :team-id (str team-id)
           :bytes bytes)
    bytes))

(def ^:private scheduler-chunk-size
  "Teams per scheduler transaction: each chunk commits on its own so
  killing the job mid-run keeps the chunks already enqueued."
  50)

(def ^:private scheduler-delay-step-ms 5000)
(def ^:private scheduler-delay-cap-ms 60000)

(defn- chunk-delay
  "Stagger delay of a scheduler chunk: per chunk, not per team, capped
  so huge backlogs do not push work too far out."
  [chunk-idx]
  (min (* chunk-idx scheduler-delay-step-ms)
       scheduler-delay-cap-ms))

(defn- submit-team-chunk
  "Enqueue one recompute job per team id in the caller transaction and
  answer how many were enqueued: call it through `tx-run!` on a cfg
  without `::db/conn` (see `db/own-transaction`) so the chunk commits
  (or rolls back) whole. A nil submit means skip-if-active found a live
  job for that team: nothing enqueued, not counted."
  [cfg team-ids delay]
  (reduce (fn [n team-id]
            (if (jobs/submit cfg
                             {::jobs/name :recompute-team-storage
                              ::jobs/queue :storage
                              ::jobs/params {:team-id team-id}
                              ::jobs/label (str team-id)
                              ::jobs/skip-if-active true
                              ::jobs/priority 10
                              ::jobs/delay delay})
              (inc n)
              n))
          0
          team-ids))

(defn execute-schedule-team-storage
  "Stream every dirty team oldest-first and enqueue one recompute job
  per team, staggered by chunk so the queue does not spike. Runs
  inside the caller read-only transaction (the job-def handler opens
  it: pgjdbc only streams the plan cursor with auto-commit off): each
  chunk is submitted through its own writable transaction (progress is
  durable: killing the run keeps the chunks already enqueued and the
  next tick picks up the rest). Teams with a live job are skipped
  (`skip-if-active` on the team id as label); their mark survives and a
  later tick picks them up. The rows are only read as values (`map
  :team-id` before `partition-all`): a plan row is a view over the open
  result set, so buffering rows would read nil columns."
  [{:keys [::db/conn] :as cfg}]
  (transduce
   (comp (map :team-id)
         (partition-all scheduler-chunk-size)
         (map-indexed vector))
   (completing
    (fn [total [chunk-idx team-ids]]
      (let [submitted (-> (db/own-transaction cfg)
                          (db/tx-run! submit-team-chunk team-ids (chunk-delay chunk-idx)))]
        (jobs/heartbeat cfg)
        (+ total ^long submitted))))
   0
   (db/plan conn [sql:get-dirty-teams] {:fetch-size 100})))

(def schema:schedule-team-storage-params
  "The scheduler takes no params."
  [:map {:closed true}])

(def schema:recompute-team-storage-params
  "A single team to recompute."
  [:map {:closed true}
   [:team-id ::sm/uuid]])

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics params)) "expected valid metrics instance"))

(defmethod ig/assert-key ::worker-job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics params)) "expected valid metrics instance"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :schedule-team-storage
   ::jobs/schema    schema:schedule-team-storage-params
   ::jobs/decoder   (sm/decoder schema:schedule-team-storage-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:schedule-team-storage-params)
   ::jobs/handler
   (fn [_context _params]
     ;; Read-only: pgjdbc only honors the plan fetch-size (a real
     ;; streaming cursor) with auto-commit off, so the whole stream
     ;; runs in one read transaction. The chunk submits open their
     ;; own writable transactions (see db/own-transaction).
     (-> (assoc cfg ::db/read-only true)
         (db/tx-run! execute-schedule-team-storage)))})

(defmethod ig/init-key ::worker-job-def
  [_ cfg]
  {::jobs/name      :recompute-team-storage
   ::jobs/schema    schema:recompute-team-storage-params
   ::jobs/decoder   (sm/decoder schema:recompute-team-storage-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:recompute-team-storage-params)
   ::jobs/handler
   (fn [_context params]
     (db/tx-run! cfg execute-recompute-team-storage params))})
