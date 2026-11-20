;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks-team-storage-test
  "Cached per-team media storage usage fed by the audit-log
  storage-usage consumer: selective queueing, dirty marking, bounded
  recompute and quota reads."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.loggers.audit.consumers :as consumer]
   [app.loggers.audit.consumers.team-storage :as consumer.team-storage]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.quotes :as quotes]
   [app.srepl.main :as srepl]
   [app.system :as sys]
   [app.tasks.team-storage :as team-storage]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)

(def ^:private test-flags
  "Quote checks run while the always-on Team Storage consumer updates its counter."
  #{:quotes})

(def ^:private real-audit-submit
  "The unstubbed audit submit: helpers.clj replaces it globally per
  test, so an end-to-end producer test rebinds it back."
  @#'audit/submit)

(defn- seed-cursors
  "The test database reset deletes the cursor rows; without them the
  drain would create the cursor at the end of the buffer and skip the
  events the test just inserted."
  [next]
  (doseq [consumer-id [consumer/nexus
                       consumer/admin-console
                       consumer/team-storage]]
    (th/db-exec! ["INSERT INTO audit_log_consumer_cursor (consumer, xid, seq)
                   VALUES (?::smallint, '0'::xid8, 0)"
                  consumer-id]))
  (next))

(t/use-fixtures :each th/database-reset seed-cursors)

(defn- consumer-cursor
  []
  (th/db-exec-one! ["SELECT xid::text AS xid, seq
                       FROM audit_log_consumer_cursor
                      WHERE consumer = ?::smallint"
                    consumer/team-storage]))

(defn- unread-count
  []
  (let [{:keys [xid seq]} (consumer-cursor)]
    (:cnt (th/db-exec-one!
           ["SELECT count(*) AS cnt FROM audit_log a
              WHERE (a.xid, a.seq) > (?::xid8, ?::bigint)"
            xid seq]))))

(defn- team-storage-consumer-cfg
  []
  {::db/pool     (:app.db/pool th/*system*)
   ::mtx/metrics (:app.metrics/metrics th/*system*)})

(defn- run-team-storage
  []
  (consumer.team-storage/run-pass
   (team-storage-consumer-cfg)))

(defn- usage-row
  [team-id]
  (th/db-exec-one! ["SELECT team_id, total_bytes, dirty_at, updated_at FROM team_storage_usage WHERE team_id = ?" team-id]))

(defn- append-row
  ([name props]
   (append-row name props "backend"))
  ([name props source]
   (#'audit/append-audit-entry
    th/*system*
    {:id         (uuid/next)
     :name       name
     :type       "action"
     :source     source
     :profile-id uuid/zero
     :ip-addr    "127.0.0.1"
     :props      props
     :context    {}
     :tracked-at (ct/now)
     :created-at (ct/now)})))

(t/deftest storage-usage-mid-recompute-shrink-re-marks-after
  ;; Production flow: a shrink landing mid-recompute is queued but not
  ;; yet drained, so the upsert clears only the stale mark; the row stays
  ;; unread in audit_log, the later drain re-marks from it and the next
  ;; tick recomputes. Nothing is lost without a trace: the audit row and
  ;; the cursor are the durable record.
  (let [prof (th/create-profile* 74 {:is-active true})
        team (:default-team-id prof)
        real @#'quotes/get-live-media-storage-bytes]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team})
      (run-team-storage)
      (with-redefs [quotes/get-live-media-storage-bytes
                    (fn [conn team-id]
                      (append-row "delete-file" {:team-id team})
                      (real conn team-id))]
        (th/run-task! :schedule-recompute-team-storage {})
        (th/run-pending-jobs))
      ;; The stale mark is gone but the mid-run event is still unread.
      (t/is (nil? (:dirty-at (usage-row team))))
      (t/is (= 1 (unread-count)))
      ;; The drain re-marks from that event; the next tick clears it.
      (run-team-storage)
      (t/is (some? (:dirty-at (usage-row team))))
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (t/is (nil? (:dirty-at (usage-row team)))))))

(t/deftest storage-usage-upsert-keeps-newer-dirty-mark
  ;; Mechanical: a dirty_at newer than the worker read survives the
  ;; upsert (CASE branch), so a mark the SUM could not include is
  ;; recomputed by the next tick instead of silently cleared.
  (let [prof (th/create-profile* 78 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team})
      (run-team-storage)
      (th/db-update! :team-storage-usage
                     {:dirty-at (ct/in-future {:hours 1})}
                     {:team-id team})
      (let [live (quotes/get-live-media-storage-bytes th/*system* team)]
        (t/is (== live (th/run-task! :recompute-team-storage {:team-id team})))
        (t/is (== live (:total-bytes (usage-row team))))
        (t/is (some? (:dirty-at (usage-row team))))))))

(t/deftest team-storage-ensure-skips-a-purged-team
  ;; A team purged between the quota read and the insert must not turn
  ;; the fail-open path into an FK error.
  (let [team (uuid/next)]
    ;; A bare team row: no projects to cascade, so the purge is clean.
    (th/db-insert! :team {:id         team
                          :name       "purged"
                          :is-default false})
    (th/db-force-delete :team {:id team})
    ;; No FK error and no orphan row: the insert simply matches nothing.
    (#'quotes/ensure-team-storage-usage th/*pool* team)
    (t/is (nil? (usage-row team)))))

(t/deftest team-storage-ensure-writes-only-when-missing
  ;; The quota ensure is a pure read when the row exists: no write
  ;; (no WAL, no speculative lock) on the upload hot path.
  (let [prof (th/create-profile* 72 {:is-active true})
        team (:default-team-id prof)
        past (ct/in-past {:days 1})]
    ;; Born dirty on first call.
    (#'quotes/ensure-team-storage-usage th/*pool* team)
    (t/is (some? (:dirty-at (usage-row team))))
    ;; Simulate a recomputed row, then prove a second call moves
    ;; nothing: neither dirty_at nor updated_at.
    (th/db-update! :team-storage-usage {:dirty-at nil :updated-at past} {:team-id team})
    (#'quotes/ensure-team-storage-usage th/*pool* team)
    (let [row (usage-row team)]
      (t/is (nil? (:dirty-at row)))
      (t/is (= (inst-ms past) (inst-ms (:updated-at row)))))))

(t/deftest storage-usage-real-delete-file-emits-the-event
  ;; As end-to-end as the harness allows (wrap-audit is decided at
  ;; boot without audit flags, so the two wrap lines run here by
  ;; hand): the real command result feeds the real event pipeline, no
  ;; hand-built event. A drift in the event name or the producer
  ;; props fails here instead of in production.
  (let [prof     (th/create-profile* 77 {:is-active true})
        team     (:default-team-id prof)
        proj     (th/create-project* 77 {:profile-id (:id prof) :team-id team})
        file     (th/create-file* 77 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})
        [mdata _] (get-in th/*system* [:app.rpc/methods :delete-file])]
    (with-redefs [cf/flags test-flags
                  audit/submit real-audit-submit]
      (let [result (th/command-raw! {::th/type :delete-file
                                     ::rpc/profile-id (:id prof)
                                     :id (:id file)})
            params (-> {::rpc/profile-id (:id prof)
                        ::rpc/request-at (ct/now)}
                       (with-meta {:app.http/request (th/make-dummy-request)}))
            event  (audit/prepare-rpc-event {::rpc/module "main" ::rpc/type "command"}
                                            mdata params result)]
        (t/is (= "delete-file" (:name event)))
        (t/is (some? (:team-id (:props event))))
        ;; The RPC wrapper already persisted the event. Do not submit the
        ;; prepared event again: full-row persistence is now always on.
        (t/is (= 1 (unread-count))))
      ;; Only the command event: the cascade job has not run yet.
      (t/is (= 1 (unread-count))))))

(t/deftest storage-usage-real-delete-font-variant-emits-the-event
  ;; A producer whose props do NOT carry :team-id (only the request
  ;; params do): a future change to ::audit/replace-props would break
  ;; it silently, so it is pinned end to end.
  (let [prof    (th/create-profile* 81 {:is-active true})
        team    (:default-team-id prof)
        variant (th/db-insert! :team-font-variant
                               {:id          (uuid/next)
                                :team-id     team
                                :profile-id  (:id prof)
                                :font-id     (uuid/next)
                                :font-family "somefont"
                                :font-weight 400
                                :font-style  "normal"})
        [mdata _] (get-in th/*system* [:app.rpc/methods :delete-font-variant])]
    (with-redefs [cf/flags test-flags
                  audit/submit real-audit-submit]
      (let [result (th/command-raw! {::th/type :delete-font-variant
                                     ::rpc/profile-id (:id prof)
                                     :team-id team
                                     :id (:id variant)})
            params (-> {::rpc/profile-id (:id prof)
                        :team-id team
                        ::rpc/request-at (ct/now)}
                       (with-meta {:app.http/request (th/make-dummy-request)}))
            event  (audit/prepare-rpc-event {::rpc/module "main" ::rpc/type "command"}
                                            mdata params result)]
        (t/is (= "delete-font-variant" (:name event)))
        (t/is (= team (:team-id (:props event))))
        ;; The RPC wrapper already persisted the event.
        (t/is (= 1 (unread-count))))
      (t/is (= 1 (unread-count))))))

(t/deftest storage-usage-drain-and-recompute
  (let [prof (th/create-profile* 1)
        team-id (:default-team-id prof)
        proj (th/create-project* 1 {:profile-id (:id prof) :team-id team-id})
        file (th/create-file* 1 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})
        mfile {:filename "sample.jpg"
               :path (th/tempfile "backend_tests/test_files/sample.jpg")
               :mtype "image/jpeg"
               :size 312043}]
    (with-redefs [cf/flags test-flags]
      (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                        ::rpc/profile-id (:id prof)
                                        :file-id (:id file)
                                        :is-local true
                                        :name "testfile"
                                        :content mfile})]
        (t/is (nil? (:error out))))
      (append-row "upload-file-media-object" {:team-id team-id :file-id (:id file)})
      (run-team-storage)
      (let [row (usage-row team-id)]
        (t/is (some? row))
        (t/is (some? (:dirty-at row))))
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (th/run-pending-jobs)
      (let [row (usage-row team-id)
            live (quotes/get-live-media-storage-bytes th/*system* team-id)]
        (t/is (pos? (:total-bytes row)))
        (t/is (== live (:total-bytes row)))
        (t/is (nil? (:dirty-at row))))
      (with-mocks [mock {:target 'app.config/get
                         :return (th/config-get-mock {:quotes-media-storage-bytes-per-team 100})}]
        (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                          ::rpc/profile-id (:id prof)
                                          :file-id (:id file)
                                          :is-local true
                                          :name "testfile2"
                                          :content mfile})]
          (t/is (some? (:error out)))
          (t/is (= :max-quote-reached (:code (ex-data (:error out))))))))))

(t/deftest media-quota-fails-open-when-the-row-is-missing
  ;; The count query must always answer one row: a team whose usage row
  ;; was never created (or a purged one) has to pass, not crash.
  (let [prof  (th/create-profile* 84 {:is-active true})
        ghost (uuid/next)]
    (with-redefs [cf/flags test-flags]
      (t/is (nil? (quotes/check! th/*system*
                                 {::quotes/id        ::quotes/media-storage-bytes-per-team
                                  ::quotes/profile-id (:id prof)
                                  ::quotes/team-id    ghost
                                  ::quotes/incr       1})))
      (t/is (nil? (usage-row ghost))))))

(t/deftest media-quota-reads-the-counter-not-the-live-sum
  ;; The cached total is the truth: with the counter at 0 the upload
  ;; passes even though the live SUM is far above the limit, and the
  ;; live query is never called on the upload path.
  (let [prof (th/create-profile* 82 {:is-active true})
        team (:default-team-id prof)
        proj (th/create-project* 82 {:profile-id (:id prof) :team-id team})
        file (th/create-file* 82 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})
        mfile {:filename "sample.jpg"
               :path (th/tempfile "backend_tests/test_files/sample.jpg")
               :mtype "image/jpeg"
               :size 312043}]
    (with-redefs [cf/flags test-flags]
      (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                        ::rpc/profile-id (:id prof)
                                        :file-id (:id file)
                                        :is-local true
                                        :name "testfile"
                                        :content mfile})]
        (t/is (nil? (:error out))))
      (th/db-update! :team-storage-usage {:total-bytes 0 :dirty-at nil} {:team-id team})
      ;; Limit above one upload but below two: the live SUM (already
      ;; ~312 KB) would reject the second one, the cached 0 must not.
      (with-mocks [mock {:target 'app.config/get
                         :return (th/config-get-mock {:quotes-media-storage-bytes-per-team 400000})}]
        (with-redefs [quotes/get-live-media-storage-bytes
                      (fn [& _] (throw (ex-info "live sum must not run" {})))]
          (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                            ::rpc/profile-id (:id prof)
                                            :file-id (:id file)
                                            :is-local true
                                            :name "testfile2"
                                            :content mfile})]
            (t/is (nil? (:error out)))))))))

(t/deftest media-quota-uses-the-counter-without-archive-flags
  ;; The always-on consumer requires persistence even when neither archive
  ;; transport is enabled. The quota therefore always reads its cached row.
  (let [prof (th/create-profile* 83 {:is-active true})
        team (:default-team-id prof)
        proj (th/create-project* 83 {:profile-id (:id prof) :team-id team})
        file (th/create-file* 83 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})
        mfile {:filename "sample.jpg"
               :path (th/tempfile "backend_tests/test_files/sample.jpg")
               :mtype "image/jpeg"
               :size 312043}]
    (with-redefs [cf/flags #{:quotes}
                  quotes/get-live-media-storage-bytes
                  (fn [& _] (throw (ex-info "live sum must not run" {})))]
      (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                        ::rpc/profile-id (:id prof)
                                        :file-id (:id file)
                                        :is-local true
                                        :name "testfile"
                                        :content mfile})]
        (t/is (nil? (:error out))))
      (t/is (some? (usage-row team)))
      (t/is (consumer/enabled? consumer.team-storage/consumer)))))

(t/deftest storage-usage-scheduler-skips-live-teams
  ;; A second scheduler run while the workers are still queued
  ;; enqueues nothing: skip-if-active on the team id as label.
  (let [prof-a (th/create-profile* 11)
        prof-b (th/create-profile* 12)
        team-a (:default-team-id prof-a)
        team-b (:default-team-id prof-b)]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team-b})
      (append-row "upload-file-media-object" {:team-id team-a})
      (run-team-storage)
      (t/is (= 2 (th/run-task! :schedule-recompute-team-storage {})))
      (t/is (= 0 (th/run-task! :schedule-recompute-team-storage {})))
      (th/run-pending-jobs)
      (t/is (nil? (:dirty-at (usage-row team-a))))
      (t/is (nil? (:dirty-at (usage-row team-b)))))))

(t/deftest storage-usage-scheduler-enqueues-by-chunk-with-per-chunk-delay
  ;; With more dirty teams than fit one chunk, every chunk commits on
  ;; its own and carries a single delay (0, 5000, 10000...): the
  ;; stagger is per chunk, not per team.
  (let [teams (mapv #(:default-team-id (th/create-profile* %)) (range 100 112))]
    (with-redefs [cf/flags test-flags
                  team-storage/scheduler-chunk-size 5]
      (doseq [team teams]
        (append-row "upload-file-media-object" {:team-id team}))
      (run-team-storage)
      (t/is (= 12 (th/run-task! :schedule-recompute-team-storage {})))
      (let [rows (th/db-exec! ["SELECT (round((extract(epoch from (scheduled_at - created_at)) * 1000)::numeric, -3))::bigint AS delay_ms FROM job WHERE name = 'recompute-team-storage'"])]
        (t/is (= 12 (count rows)))
        (t/is (= {0 5, 5000 5, 10000 2} (frequencies (map :delay-ms rows))))))))

(t/deftest storage-usage-scheduler-reads-at-most-the-tick-limit
  ;; A backlog bigger than the limit is read in ticks: the scan cuts at
  ;; `scheduler-limit` and the teams left out keep their mark, so the
  ;; next tick reads them instead of losing them.
  (let [teams (mapv #(:default-team-id (th/create-profile* %)) (range 140 143))]
    (with-redefs [cf/flags test-flags
                  team-storage/scheduler-limit 2]
      (doseq [team teams]
        (append-row "upload-file-media-object" {:team-id team}))
      (run-team-storage)
      (t/is (= 2 (th/run-task! :schedule-recompute-team-storage {})))
      (th/run-pending-jobs)
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {}))))))

(t/deftest storage-usage-scheduler-reads-every-page-of-the-plan
  ;; One row per round trip: the plan is consumed page by page and every
  ;; team is enqueued, none left behind in an unread page.
  (let [teams (mapv #(:default-team-id (th/create-profile* %)) (range 144 147))]
    (with-redefs [cf/flags test-flags
                  team-storage/scheduler-fetch-size 1
                  team-storage/scheduler-chunk-size 1]
      (doseq [team teams]
        (append-row "upload-file-media-object" {:team-id team}))
      (run-team-storage)
      (t/is (= 3 (th/run-task! :schedule-recompute-team-storage {})))
      (t/is (= (set (map str teams))
               (into #{}
                     (map :label)
                     (th/db-exec! ["SELECT label FROM job WHERE name = 'recompute-team-storage'"])))))))

(t/deftest storage-usage-scheduler-partial-progress-survives-mid-run-failure
  ;; Chunks commit independently: a failure mid-run keeps the chunks
  ;; already enqueued, and the next tick only submits the rest
  ;; (the live ones are skipped).
  (let [teams (mapv #(:default-team-id (th/create-profile* %)) (range 120 132))]
    (with-redefs [cf/flags test-flags
                  team-storage/scheduler-chunk-size 5]
      (doseq [team teams]
        (append-row "upload-file-media-object" {:team-id team}))
      (run-team-storage)
      (let [calls (atom 0)
            orig  jobs/submit]
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (with-redefs [jobs/submit (fn [cfg opts]
                                                   (when (> (swap! calls inc) 7)
                                                     (throw (ex-info "boom" {})))
                                                   (orig cfg opts))]
                         (th/run-task! :schedule-recompute-team-storage {}))))
        ;; The first chunk committed before the failure; the second
        ;; rolled back whole.
        (t/is (= 5 (count (th/db-query :job {:name "recompute-team-storage"}))))
        ;; The next tick resumes: 5 live skipped, 7 fresh.
        (t/is (= 7 (th/run-task! :schedule-recompute-team-storage {})))
        (t/is (= 12 (count (th/db-query :job {:name "recompute-team-storage"}))))))))

(t/deftest audit-submit-does-not-swallow-failures
  ;; A lost event is a stale consumer: the failure has to surface, or
  ;; the counter drifts with no trace. (An event without a name fails
  ;; validation, which is enough to prove there is no catch.)
  ;; (the fixture stubs `submit`; this one is the real fn)
  (t/is (thrown? Exception
                 (real-audit-submit th/*system* {:type "action"}))))

(t/deftest storage-usage-mark-inside-recompute-survives-the-clear
  ;; A mark drained while the SUM runs is newer than the recompute read,
  ;; so the clear keeps it and the next tick recomputes the team. With
  ;; the mark resolved as "the oldest wins" the consumer dropped it (the
  ;; row was already dirty) and the CASE then cleared the stale one,
  ;; losing the change until the weekly sweep.
  (let [prof (th/create-profile* 79 {:is-active true})
        team (:default-team-id prof)
        real @#'quotes/get-live-media-storage-bytes]
    (with-redefs [cf/flags test-flags]
      ;; The first event marks the team; the recompute reads that mark.
      (append-row "upload-file-media-object" {:team-id team})
      (run-team-storage)
      (t/is (some? (:dirty-at (usage-row team))))
      (with-redefs [quotes/get-live-media-storage-bytes
                    (fn [conn team-id]
                      (append-row "delete-file" {:team-id team})
                      (run-team-storage)
                      (real conn team-id))]
        (th/run-task! :schedule-recompute-team-storage {})
        (th/run-pending-jobs))
      ;; The mid-run mark outlived the clear.
      (t/is (some? (:dirty-at (usage-row team)))))))

(t/deftest storage-usage-scheduler-sweeps-stale-rows
  ;; A mark lost on the way (failed audit insert, flag off) would keep
  ;; the counter low for ever: rows nobody refreshed for a week come
  ;; back to the queue, and fresh ones stay out.
  (let [prof    (th/create-profile* 87 {:is-active true})
        stale   (:id (th/create-team* 87 {:profile-id (:id prof)}))
        fresh   (:id (th/create-team* 88 {:profile-id (:id prof)}))
        old     (ct/in-past {:days 8})]
    (th/db-insert! :team-storage-usage {:team-id stale :total-bytes 10 :updated-at old})
    (th/db-insert! :team-storage-usage {:team-id fresh :total-bytes 10})
    (with-redefs [cf/flags test-flags]
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (let [rows (th/db-exec! ["SELECT label FROM job WHERE name = 'recompute-team-storage'"])]
        (t/is (= [(str stale)] (mapv :label rows)))))))

(t/deftest storage-usage-scheduler-enqueues-a-dirty-and-stale-team-once
  ;; A row can carry a mark and be stale at the same time (the mark
  ;; survived, the refresh is old). The two branches of the scan cover
  ;; disjoint rows (`dirty_at IS NOT NULL` and `dirty_at IS NULL`), so it
  ;; is enqueued once: a UNION ALL without the guard would enqueue it
  ;; twice.
  (let [prof  (th/create-profile* 88 {:is-active true})
        team  (:default-team-id prof)
        old   (ct/in-past {:days 8})]
    (th/db-insert! :team-storage-usage {:team-id team :total-bytes 10
                                        :dirty-at old :updated-at old})
    (with-redefs [cf/flags test-flags]
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (t/is (= 1 (count (th/db-query :job {:name "recompute-team-storage"})))))))

(t/deftest storage-usage-scheduler-enqueues-on-the-storage-queue
  ;; The recompute runs the slow per-team SUM, so it owns a queue:
  ;; it must never share the default runner with the delete cascades.
  (let [prof (th/create-profile* 75 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team})
      (run-team-storage)
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (let [row (th/db-exec-one! ["SELECT queue FROM job WHERE name = 'recompute-team-storage'"])]
        (t/is (= "storage" (:queue row)))))))

(t/deftest storage-usage-worker-skips-purged-team
  ;; A team purged after scheduling must not fail the worker: the
  ;; upsert only touches existing teams, so it ends normally with no
  ;; row instead of violating the foreign key into retries.
  (let [prof (th/create-profile* 65 {:is-active true})
        team (:id (th/create-team* 65 {:profile-id (:id prof)}))]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team})
      (run-team-storage)
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (with-redefs [sys/system th/*system*]
        (srepl/delete-team! (str team)))
      (th/run-task! :objects-gc {:skip-delay true})
      (t/is (nil? (th/db-get :team {:id team} {::db/remove-deleted false})))
      (t/is (== 0 (th/run-task! :recompute-team-storage {:team-id team})))
      (t/is (nil? (usage-row team))))))

(t/deftest storage-usage-failed-worker-keeps-dirty-and-reschedules
  ;; A failing worker fails its own job and leaves the team dirty; the
  ;; next scheduler tick enqueues it again (no live job left to skip).
  (let [prof    (th/create-profile* 13)
        team-id (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team-id})
      (run-team-storage)
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (let [live-fn quotes/get-live-media-storage-bytes]
        (with-redefs [quotes/get-live-media-storage-bytes
                      (fn [conn id]
                        (if (= id team-id)
                          (throw (ex-info "boom" {}))
                          (live-fn conn id)))]
          (try (th/run-task! :recompute-team-storage {:team-id team-id})
               (catch Throwable _ nil))))
      (t/is (some? (:dirty-at (usage-row team-id))))
      ;; The finished job is gone (reaped by the jobs GC in
      ;; production); the next tick schedules the team again and this
      ;; time it succeeds.
      (th/db-force-delete :job {:name "recompute-team-storage"})
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (th/run-pending-jobs)
      (let [row  (usage-row team-id)
            live (quotes/get-live-media-storage-bytes th/*system* team-id)]
        (t/is (nil? (:dirty-at row)))
        (t/is (== live (:total-bytes row)))))))

(t/deftest storage-usage-drain-skips-purged-teams
  ;; A team purged between queueing and drain must not wedge the chunk:
  ;; it is skipped and the surviving teams are still marked.
  (let [prof        (th/create-profile* 31)
        team-doomed (:id (th/create-team* 31 {:profile-id (:id prof)}))
        team-alive  (:id (th/create-team* 32 {:profile-id (:id prof)}))]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team-doomed})
      (append-row "upload-file-media-object" {:team-id team-alive})
      (th/command! {::th/type :delete-team
                    ::rpc/profile-id (:id prof)
                    :id team-doomed})
      (th/run-pending-jobs)
      (th/run-task! :objects-gc {:skip-delay true})
      (t/is (nil? (th/db-get :team {:id team-doomed} {::db/remove-deleted false})))
      (run-team-storage)
      ;; The drain completed instead of aborting the chunk in a loop:
      ;; the row was passed and the surviving team is marked.
      (t/is (= 0 (unread-count)))
      (t/is (some? (:dirty-at (usage-row team-alive)))))))

(t/deftest storage-usage-restore-team-moves-totals
  ;; Deleting a team zeroes its counter; restoring it brings the
  ;; media back and marks the team dirty again.
  (let [prof  (th/create-profile* 61 {:is-active true})
        team  (:id (th/create-team* 61 {:profile-id (:id prof)}))
        proj  (th/create-project* 61 {:profile-id (:id prof) :team-id team})
        file  (th/create-file* 61 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})
        mfile {:filename "sample.jpg"
               :path (th/tempfile "backend_tests/test_files/sample.jpg")
               :mtype "image/jpeg"
               :size 312043}]
    (with-redefs [cf/flags test-flags]
      (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                        ::rpc/profile-id (:id prof)
                                        :file-id (:id file)
                                        :is-local true
                                        :name "testfile"
                                        :content mfile})]
        (t/is (nil? (:error out))))
      ;; Upload already persisted its full audit row for the always-on
      ;; consumer.
      (run-team-storage)
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (let [live (quotes/get-live-media-storage-bytes th/*system* team)]
        (t/is (pos? live))
        (t/is (== live (:total-bytes (usage-row team)))))
      (with-redefs [sys/system th/*system*]
        (srepl/delete-team! (str team)))
      (run-team-storage)
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (t/is (== 0 (:total-bytes (usage-row team))))
      (with-redefs [sys/system th/*system*]
        (srepl/restore-team! (str team)))
      (t/is (= 1 (unread-count)))
      (run-team-storage)
      (t/is (some? (:dirty-at (usage-row team))))
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (let [live (quotes/get-live-media-storage-bytes th/*system* team)]
        (t/is (pos? live))
        (t/is (== live (:total-bytes (usage-row team))))))))

(t/deftest storage-usage-restore-file-marks-dirty
  (let [prof (th/create-profile* 62 {:is-active true})
        team (:default-team-id prof)
        proj (th/create-project* 62 {:profile-id (:id prof) :team-id team})
        file (th/create-file* 62 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})]
    (with-redefs [cf/flags test-flags]
      (let [out (th/command! {::th/type :delete-file
                              ::rpc/profile-id (:id prof)
                              :id (:id file)})]
        (t/is (nil? (:error out))))
      (th/run-pending-jobs)
      (run-team-storage)
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (with-redefs [sys/system th/*system*]
        (srepl/restore-file! (str (:id file))))
      (t/is (= 1 (unread-count)))
      (run-team-storage)
      (t/is (some? (:dirty-at (usage-row team)))))))

(t/deftest storage-usage-restore-project-marks-dirty
  (let [prof (th/create-profile* 63 {:is-active true})
        team (:default-team-id prof)
        proj (th/create-project* 63 {:profile-id (:id prof) :team-id team})]
    (with-redefs [cf/flags test-flags]
      (let [out (th/command! {::th/type :delete-project
                              ::rpc/profile-id (:id prof)
                              :id (:id proj)})]
        (t/is (nil? (:error out))))
      (th/run-pending-jobs)
      (run-team-storage)
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (with-redefs [sys/system th/*system*]
        (srepl/restore-project! (str (:id proj))))
      (t/is (= 1 (unread-count)))
      (run-team-storage)
      (t/is (some? (:dirty-at (usage-row team)))))))

(t/deftest storage-usage-restore-profile-marks-dirty
  (let [prof (th/create-profile* 64 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (with-redefs [sys/system th/*system*]
        (srepl/delete-profile! (str (:id prof))))
      (run-team-storage)
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (with-redefs [sys/system th/*system*]
        (srepl/restore-profile! (str (:id prof))))
      (t/is (= 1 (unread-count)))
      (run-team-storage)
      (t/is (some? (:dirty-at (usage-row team)))))))

(t/deftest storage-usage-move-project-moves-totals
  ;; Moving a project between teams marks both teams dirty and moves
  ;; the totals: the origin stops counting the media, the destination
  ;; starts.
  (let [prof   (th/create-profile* 41 {:is-active true})
        team-a (:default-team-id prof)
        team-b (:id (th/create-team* 41 {:profile-id (:id prof)}))
        proj-a (th/create-project* 41 {:profile-id (:id prof) :team-id team-a})
        proj-b (th/create-project* 42 {:profile-id (:id prof) :team-id team-b})
        file   (th/create-file* 41 {:profile-id (:id prof) :project-id (:id proj-a) :is-shared false})
        mfile  {:filename "sample.jpg"
                :path (th/tempfile "backend_tests/test_files/sample.jpg")
                :mtype "image/jpeg"
                :size 312043}]
    (with-redefs [cf/flags test-flags]
      (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                        ::rpc/profile-id (:id prof)
                                        :file-id (:id file)
                                        :is-local true
                                        :name "testfile"
                                        :content mfile})]
        (t/is (nil? (:error out))))
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (let [live-a (quotes/get-live-media-storage-bytes th/*system* team-a)]
        (t/is (pos? live-a))
        (t/is (== live-a (:total-bytes (usage-row team-a))))))
    ;; The move emits both teams explicitly in its event props.
    (let [wrapped (th/command-raw! {::th/type :move-project
                                    ::rpc/profile-id (:id prof)
                                    :project-id (:id proj-a)
                                    :team-id team-b})
          props   (::audit/props (meta wrapped))]
      (t/is (= team-a (:source-team-id props)))
      (t/is (= team-b (:dest-team-id props)))
      (with-redefs [cf/flags test-flags]
        ;; The real team ids from the command, plus what wrap-audit and
        ;; the team enrichment would add (destination team and project).
        (append-row "move-project" (assoc props
                                          :team-id team-b
                                          :project-id (:id proj-a)))
        (run-team-storage)
        (t/is (some? (:dirty-at (usage-row team-a))))
        (t/is (some? (:dirty-at (usage-row team-b))))
        (th/run-task! :schedule-recompute-team-storage {})
        (th/run-pending-jobs)
        (t/is (== 0 (:total-bytes (usage-row team-a))))
        (let [live-b (quotes/get-live-media-storage-bytes th/*system* team-b)]
          (t/is (pos? live-b))
          (t/is (== live-b (:total-bytes (usage-row team-b)))))))))

(t/deftest storage-usage-move-files-moves-totals
  ;; Moving a file to a project of another team marks both teams dirty
  ;; and moves the totals.
  (let [prof   (th/create-profile* 42 {:is-active true})
        team-a (:default-team-id prof)
        team-b (:id (th/create-team* 42 {:profile-id (:id prof)}))
        proj-a (th/create-project* 43 {:profile-id (:id prof) :team-id team-a})
        proj-b (th/create-project* 44 {:profile-id (:id prof) :team-id team-b})
        file   (th/create-file* 42 {:profile-id (:id prof) :project-id (:id proj-a) :is-shared false})
        mfile  {:filename "sample.jpg"
                :path (th/tempfile "backend_tests/test_files/sample.jpg")
                :mtype "image/jpeg"
                :size 312043}]
    (with-redefs [cf/flags test-flags]
      (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                        ::rpc/profile-id (:id prof)
                                        :file-id (:id file)
                                        :is-local true
                                        :name "testfile"
                                        :content mfile})]
        (t/is (nil? (:error out))))
      (th/run-task! :schedule-recompute-team-storage {})
      (th/run-pending-jobs)
      (t/is (pos? (:total-bytes (usage-row team-a)))))
    ;; The move emits both teams explicitly in its event props.
    (let [wrapped (th/command-raw! {::th/type :move-files
                                    ::rpc/profile-id (:id prof)
                                    :project-id (:id proj-b)
                                    :ids #{(:id file)}})
          props   (::audit/props (meta wrapped))]
      (t/is (= team-a (:source-team-id props)))
      (t/is (= team-b (:dest-team-id props)))
      (with-redefs [cf/flags test-flags]
        ;; The real origin ids from the command, plus what wrap-audit
        ;; and the team enrichment would add.
        (append-row "move-files" (assoc props
                                        :team-id team-b
                                        :project-id (:id proj-b)))
        (run-team-storage)
        (t/is (some? (:dirty-at (usage-row team-a))))
        (t/is (some? (:dirty-at (usage-row team-b))))
        (th/run-task! :schedule-recompute-team-storage {})
        (th/run-pending-jobs)
        (t/is (== 0 (:total-bytes (usage-row team-a))))
        (let [live-b (quotes/get-live-media-storage-bytes th/*system* team-b)]
          (t/is (pos? live-b))
          (t/is (== live-b (:total-bytes (usage-row team-b)))))))))

(t/deftest storage-usage-missing-row-fails-open
  (let [prof (th/create-profile* 1)
        team-id (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (quotes/check! th/*system* {::quotes/id ::quotes/media-storage-bytes-per-team
                                  ::quotes/profile-id (:id prof)
                                  ::quotes/team-id team-id
                                  ::quotes/incr 0})
      (let [row (usage-row team-id)]
        (t/is (some? row))
        (t/is (some? (:dirty-at row)))
        (t/is (= 0 (:total-bytes row))))
      ;; The row born on quota check is picked up by the next tick.
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (th/run-pending-jobs)
      (let [row  (usage-row team-id)
            live (quotes/get-live-media-storage-bytes th/*system* team-id)]
        (t/is (nil? (:dirty-at row)))
        (t/is (== live (:total-bytes row)))))))

(t/deftest storage-usage-file-gc-stays-silent-without-trims
  ;; The event is gated on actually trimming media: without that, a
  ;; cron walk over every file would flood the audit buffer with one row
  ;; per file it touches.
  (let [prof (th/create-profile* 2)
        team-id (:default-team-id prof)
        proj (th/create-project* 2 {:profile-id (:id prof) :team-id team-id})
        file (th/create-file* 2 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})
        submitted (atom nil)]
    (with-redefs [cf/flags test-flags
                  audit/submit (fn [_ event] (reset! submitted event) nil)]
      (let [revn (:revn (th/db-exec-one! ["SELECT revn FROM file WHERE id = ?" (:id file)]))]
        (th/run-task! :file-gc {:file-id (:id file) :revn revn}))
      (t/is (nil? @submitted)))))

(t/deftest storage-usage-file-gc-emits-event
  (let [prof (th/create-profile* 1)
        team-id (:default-team-id prof)
        proj (th/create-project* 1 {:profile-id (:id prof) :team-id team-id})
        file (th/create-file* 1 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})
        mfile {:filename "sample.jpg"
               :path (th/tempfile "backend_tests/test_files/sample.jpg")
               :mtype "image/jpeg"
               :size 312043}
        submitted (atom nil)]
    (with-redefs [cf/flags test-flags
                  audit/submit (fn [_ event] (reset! submitted event) nil)]
      (let [out (th/multipart-command! {::th/type :upload-file-media-object
                                        ::rpc/profile-id (:id prof)
                                        :file-id (:id file)
                                        :is-local true
                                        :name "testfile"
                                        :content mfile})]
        (t/is (nil? (:error out))))
      (let [revn (:revn (th/db-exec-one! ["SELECT revn FROM file WHERE id = ?" (:id file)]))]
        (th/run-task! :file-gc {:file-id (:id file) :revn revn}))
      (let [event @submitted]
        (t/is (= "file-gc" (:name event)))
        (t/is (= "action" (:type event)))
        (t/is (= "cron-task" (-> event :context :triggered-by)))
        (t/is (= (:id file) (-> event :props :file-id)))
        (t/is (= team-id (-> event :props :team-id)))))
    (with-redefs [cf/flags test-flags]
      (append-row "file-gc" {:file-id (:id file) :team-id team-id})
      (run-team-storage)
      (t/is (some? (:dirty-at (usage-row team-id))))
      (t/is (= 1 (th/run-task! :schedule-recompute-team-storage {})))
      (th/run-pending-jobs)
      (t/is (nil? (:dirty-at (usage-row team-id))))
      (append-row "delete-object-with-cascade" {:cascade {:team-ids [team-id]}})
      (run-team-storage)
      (t/is (some? (:dirty-at (usage-row team-id)))))))
