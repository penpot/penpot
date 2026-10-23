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
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.loggers.audit.consumer-task :as consumer]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.audit :as cmd-audit]
   [app.rpc.quotes :as quotes]
   [app.srepl.main :as srepl]
   [app.system :as sys]
   [app.tasks.team-storage :as team-storage]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private test-flags
  "Quote checks run (:quotes), events are stored (:audit-log), the
  events consumer queues them (:event-consumer) and the team-storage
  reaction runs (:storage-usage)."
  #{:quotes :audit-log :event-consumer :storage-usage})

(def ^:private real-audit-submit
  "The unstubbed audit submit: helpers.clj replaces it globally per
  test, so an end-to-end producer test rebinds it back."
  @#'audit/submit)

(defn- events-pendings
  "The pendings of the events consumer (id 3): it is not selective, it
  takes the whole stream."
  []
  (th/db-exec! ["SELECT audit_log_id, consumer FROM audit_log_pending WHERE consumer = 3"]))

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

(t/deftest events-queueing-takes-every-event
  ;; The events consumer is not selective: the whole stream queues and
  ;; each reaction decides what concerns it.
  (with-redefs [cf/flags #{:audit-log :event-consumer}]
    (append-row "upload-file-media-object" {})
    (append-row "navigate" {})
    (append-row "delete-file" {})
    (t/is (= 3 (count (events-pendings))))))

(t/deftest storage-usage-reaction-ignores-hard-delete-objects
  ;; Physical purges cannot change the live total (they only remove rows
  ;; the SUM already excludes), so the reaction does not claim them:
  ;; this also keeps purged teams from ever hitting the usage-table
  ;; foreign key.
  (let [prof (th/create-profile* 62 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (append-row "hard-delete-objects" {:team-ids [team]})
      (th/run-task! :audit-log-consumer-events {})
      (t/is (nil? (:dirty-at (usage-row team)))))))

(t/deftest events-queueing-skips-telemetry-shadows
  ;; A telemetry shadow keeps the name of the full row, so queueing it
  ;; would dispatch the same event twice.
  (with-redefs [cf/flags #{:audit-log :telemetry :event-consumer}]
    (append-row "upload-file-media-object" {} "backend")
    (append-row "upload-file-media-object" {} "telemetry:backend")
    (t/is (= 1 (count (events-pendings))))))

(t/deftest storage-usage-reaction-covers-every-listed-name
  ;; Pins the reaction contract for every name in
  ;; team-storage-event-names: each one marks the teams its key map
  ;; resolves, using the real prop shape. It fails when a name is
  ;; added without its case; the producers themselves are pinned by the
  ;; end-to-end tests (delete-file, delete-font-variant, moves,
  ;; file-gc), which go through real command output.
  (let [prof   (th/create-profile* 71 {:is-active true})
        team   (:default-team-id prof)
        team-b (:id (th/create-team* 71 {:profile-id (:id prof)}))
        proj   (th/create-project* 71 {:profile-id (:id prof) :team-id team})
        file   (th/create-file* 71 {:profile-id (:id prof) :project-id (:id proj) :is-shared false})
        cases  [["upload-file-media-object" {:team-id team :file-id (:id file)}]
                ["create-file-media-object-from-url" {:team-id team}]
                ["clone-file-media-object" {:team-id team :file-id (:id file)}]
                ["assemble-file-media-object" {:team-id team}]
                ["create-file" {:team-id team :project-id (:id proj)}]
                ["create-font-variant" {:team-id team}]
                ["delete-font" {:team-id team}]
                ["delete-font-variant" {:team-id team}]
                ["delete-file" {:team-id team :file-id (:id file)}]
                ["delete-object-with-cascade" {:team-ids [team]
                                               :cascade {:team-ids [team-b]
                                                         :project-ids []
                                                         :file-ids []}}]
                ["move-files" {:source-team-id team :dest-team-id team-b}]
                ["move-project" {:source-team-id team-b :dest-team-id team}]
                ["permanently-delete-team-files" {:team-id team}]
                ["restore-deleted-team-files" {:team-id team-b}]
                ["restore-file" {:team-id team}]
                ["restore-profile" {:team-ids [team team-b]}]
                ["restore-project" {:team-id team-b}]
                ["restore-team" {:team-id team}]
                ["file-gc" {:team-id team :file-id (:id file)}]]]
    (t/is (= team-storage/team-storage-event-names (set (map first cases))))
    (with-redefs [cf/flags test-flags]
      (doseq [[nm props] cases]
        (append-row nm props))
      ;; Negatives: the reaction does not claim them.
      (append-row "hard-delete-objects" {:team-ids [team]})
      (append-row "navigate" {:route "dashboard"})
      (th/run-task! :audit-log-consumer-events {})
      (t/is (some? (:dirty-at (usage-row team))))
      (t/is (some? (:dirty-at (usage-row team-b)))))))

(t/deftest storage-usage-mid-recompute-shrink-re-marks-after
  ;; Production flow: a shrink landing mid-recompute is queued but not
  ;; yet drained, so the upsert clears only the stale mark; the later
  ;; drain re-marks from the pending and the next tick recomputes.
  ;; Nothing is lost without a trace: the pending is the durable
  ;; record.
  (let [prof (th/create-profile* 74 {:is-active true})
        team (:default-team-id prof)
        real @#'quotes/get-live-media-storage-bytes]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team})
      (th/run-task! :audit-log-consumer-events {})
      (with-redefs [quotes/get-live-media-storage-bytes
                    (fn [conn team-id]
                      (append-row "delete-file" {:team-id team})
                      (real conn team-id))]
        (th/run-task! :schedule-team-storage {})
        (th/run-pending-jobs))
      ;; The stale mark is gone but the mid-run pending survived it.
      (t/is (nil? (:dirty-at (usage-row team))))
      (t/is (= 1 (count (events-pendings))))
      ;; The drain re-marks from the pending; the next tick clears it.
      (th/run-task! :audit-log-consumer-events {})
      (t/is (some? (:dirty-at (usage-row team))))
      (th/run-task! :schedule-team-storage {})
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
      (th/run-task! :audit-log-consumer-events {})
      (th/db-update! :team-storage-usage
                     {:dirty-at (ct/in-future {:hours 1})}
                     {:team-id team})
      (let [live (quotes/get-live-media-storage-bytes th/*system* team)]
        (t/is (== live (th/run-task! :recompute-team-storage {:team-id team})))
        (t/is (== live (:total-bytes (usage-row team))))
        (t/is (some? (:dirty-at (usage-row team))))))))

(t/deftest events-queueing-needs-flag
  (with-redefs [cf/flags #{:audit-log}]
    (append-row "upload-file-media-object" {})
    (t/is (= 0 (count (events-pendings))))))

(t/deftest queue-pendings-batches-past-the-threshold
  ;; Above `single-insert-max-ids` the helper must chunk instead of one
  ;; statement: same rows, no parameter-limit risk.
  (let [prof (th/create-profile* 80 {:is-active true})
        ids  (mapv (fn [_]
                     (let [row (th/db-insert! :audit-log
                                              {:id         (uuid/next)
                                               :name       "create-file"
                                               :type       "action"
                                               :source     "backend"
                                               :profile-id (:id prof)
                                               :ip-addr    (db/inet "127.0.0.1")
                                               :props      (db/tjson {})
                                               :context    (db/tjson {})
                                               :tracked-at (ct/now)
                                               :created-at (ct/now)})]
                       (:id row)))
                   (range 5))]
    (with-redefs [consumer/single-insert-max-ids 2]
      (consumer/queue-pendings th/*system* ids consumer/consumer-nexus))
    (t/is (= 5 (count (th/db-exec! ["SELECT * FROM audit_log_pending WHERE consumer = 1"]))))))

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

(defn- admin-console-pendings
  []
  (th/db-exec! ["SELECT audit_log_id, consumer FROM audit_log_pending WHERE consumer = 2"]))

(t/deftest admin-console-queueing-skips-telemetry-shadows
  ;; A shadow keeps the event name, so queueing it would ingest the
  ;; same allowlisted event twice (once full, once anonymized).
  (with-redefs [cf/flags #{:audit-log :telemetry :admin-console}]
    (append-row "create-file" {:team-id (uuid/next)} "backend")
    (append-row "create-file" {:team-id (uuid/next)} "telemetry:backend")
    (t/is (= 1 (count (admin-console-pendings))))))

(t/deftest admin-console-bulk-ingest-skips-telemetry-shadows
  ;; Same rule on the bulk ingest path.
  (let [prof (th/create-profile* 79 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log :telemetry :admin-console}]
      (@#'cmd-audit/handle-events
       {::db/pool th/*pool* ::mtx/metrics (th/metrics-stub)}
       (with-meta {::rpc/profile-id (:id prof)
                   ::rpc/request-at (ct/now)
                   :events [{:name "create-file" :type "action"
                             :props {} :timestamp (ct/now)}]}
         {:app.http/request (th/make-dummy-request)}))
      (t/is (= 1 (count (admin-console-pendings)))))))

(t/deftest admin-console-queueing-is-selective
  ;; Only allowlisted event names queue admin-console pendings.
  (with-redefs [cf/flags #{:audit-log :admin-console}]
    (append-row "create-file" {:team-id (uuid/next)})
    (append-row "navigate" {:route "dashboard"})
    (t/is (= 1 (count (admin-console-pendings))))))

(t/deftest admin-console-bulk-ingest-queueing-is-selective
  ;; The bulk frontend ingest queues admin-console pendings only for
  ;; allowlisted names (this keeps its browser events working).
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log :admin-console}]
      (@#'cmd-audit/handle-events
       {::db/pool th/*pool* ::mtx/metrics (th/metrics-stub)}
       (with-meta {::rpc/profile-id (:id prof)
                   ::rpc/request-at (ct/now)
                   :events [{:name "create-file" :type "action"
                             :props {} :timestamp (ct/now)}
                            {:name "navigate" :type "action"
                             :props {} :timestamp (ct/now)}]}
         {:app.http/request (th/make-dummy-request)}))
      (t/is (= 1 (count (admin-console-pendings)))))))

(t/deftest admin-console-bulk-ingest-queueing-needs-flag
  ;; Disabled consumers queue nothing, even for allowlisted names and
  ;; even for nexus (the default call): no consumer means no pendings.
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log}]
      (@#'cmd-audit/handle-events
       {::db/pool th/*pool* ::mtx/metrics (th/metrics-stub)}
       (with-meta {::rpc/profile-id (:id prof)
                   ::rpc/request-at (ct/now)
                   :events [{:name "create-file" :type "action"
                             :props {} :timestamp (ct/now)}
                            {:name "navigate" :type "action"
                             :props {} :timestamp (ct/now)}]}
         {:app.http/request (th/make-dummy-request)}))
      (t/is (= 0 (count (admin-console-pendings))))
      (t/is (= 0 (count (th/db-exec! ["select * from audit_log_pending"])))))))

(t/deftest storage-usage-chunk-warns-on-a-nil-team-id
  ;; A key present with a nil value is the producer-failed shape: it
  ;; must not pass as "carries its keys".
  (let [records (atom [])
        _       (add-watch l/log-record ::capture
                           (fn [_ _ _ record] (swap! records conj record)))]
    (with-redefs [cf/flags test-flags]
      (append-row "delete-file" {:team-id nil})
      (try
        (th/run-task! :audit-log-consumer-events {})
        (finally
          (remove-watch l/log-record ::capture)))
      (t/is (= 0 (count (events-pendings))))
      (t/is (let [deadline (+ (System/currentTimeMillis) 5000)]
              (loop []
                (if (some #(= "storage-usage chunk resolved no team ids"
                              (get (::l/props %) :hint))
                          @records)
                  true
                  (when (< (System/currentTimeMillis) deadline)
                    (Thread/sleep 50)
                    (recur)))))))))

(t/deftest storage-usage-chunk-does-not-warn-on-explicit-empty-teams
  ;; A profile that owns no team sends :team-ids [] on purpose: that is
  ;; a fact, not a producer that lost its keys.
  (let [records (atom [])
        _       (add-watch l/log-record ::capture
                           (fn [_ _ _ record] (swap! records conj record)))]
    (with-redefs [cf/flags test-flags]
      (append-row "restore-profile" {:id (uuid/next) :team-ids []})
      (try
        (th/run-task! :audit-log-consumer-events {})
        (finally
          (remove-watch l/log-record ::capture)))
      (t/is (= 0 (count (events-pendings))))
      (t/is (not-any? #(= "storage-usage chunk resolved no team ids"
                          (get (::l/props %) :hint))
                      @records)))))

(t/deftest storage-usage-drain-skips-rows-locked-by-an-archive
  ;; The drain does not archive and its mark is idempotent, so it must
  ;; not wait on an archive job holding the oldest rows: it skips them
  ;; (SKIP LOCKED) and processes the rest, instead of hitting
  ;; lock_timeout and aborting the run.
  (let [prof   (th/create-profile* 85 {:is-active true})
        team-a (:default-team-id prof)
        team-b (:id (th/create-team* 85 {:profile-id (:id prof)}))]
    (with-redefs [cf/flags test-flags]
      (let [locked (append-row "upload-file-media-object" {:team-id team-a})]
        (append-row "upload-file-media-object" {:team-id team-b})
        ;; Make the locked row the oldest, so it is the one the drain
        ;; would pick first.
        (th/db-update! :audit-log {:created-at (ct/in-past {:minutes 1})} {:id (:id locked)})
        (with-open [conn (db/open th/*system*)]
          (db/exec! conn ["BEGIN"])
          (db/exec! conn ["SELECT id FROM audit_log WHERE id = ? FOR UPDATE" (:id locked)])
          (try
            (th/run-task! :audit-log-consumer-events {})
            (finally
              (db/exec! conn ["ROLLBACK"])))
          ;; The locked row stayed pending; the other one drained.
          (t/is (= 1 (count (events-pendings))))
          (t/is (nil? (:dirty-at (usage-row team-a))))
          (t/is (some? (:dirty-at (usage-row team-b)))))))))

(t/deftest events-run-summary-names-its-work
  ;; The run summary must not say "events archived": this consumer
  ;; dispatches the stream and has no destination.
  (t/is (= "events dispatched"
           (#'consumer/consumer-summary consumer/consumer-events)))
  (t/is (= "events archived"
           (#'consumer/consumer-summary consumer/consumer-nexus))))

(t/deftest storage-usage-chunk-warns-on-empty-resolution
  ;; A relevant event that resolves no team is a producer that stopped
  ;; carrying team keys: the chunk logs it instead of going stale in
  ;; silence. (Only the keyless event is appended: any valid team
  ;; would mark and silence the warn by design. Logging is async, so
  ;; the assert polls like objects-gc-test does.)
  (let [records (atom [])
        _       (add-watch l/log-record ::capture
                           (fn [_ _ _ record] (swap! records conj record)))]
    (with-redefs [cf/flags test-flags]
      (append-row "delete-file" {})
      (try
        (th/run-task! :audit-log-consumer-events {})
        (finally
          (remove-watch l/log-record ::capture)))
      (t/is (= 0 (count (events-pendings))))
      (t/is (let [deadline (+ (System/currentTimeMillis) 5000)]
              (loop []
                (if (some #(= "storage-usage chunk resolved no team ids"
                              (get (::l/props %) :hint))
                          @records)
                  true
                  (when (< (System/currentTimeMillis) deadline)
                    (Thread/sleep 50)
                    (recur)))))))))

(t/deftest storage-usage-real-delete-file-queues-pending
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
        (audit/submit th/*system* event))
      ;; Only the command event: the cascade job has not run yet.
      (t/is (= 1 (count (events-pendings)))))))

(t/deftest storage-usage-real-delete-font-variant-queues-pending
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
        (audit/submit th/*system* event))
      (t/is (= 1 (count (events-pendings)))))))

(t/deftest storage-usage-drain-skips-invalid-team-id
  ;; One bad row cannot wedge the queue: the event is logged and
  ;; skipped, the chunk completes, and the valid teams still mark.
  (let [prof (th/create-profile* 73 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id "not-a-uuid"})
      (append-row "upload-file-media-object" {:team-id team})
      (t/is (= 2 (count (events-pendings))))
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 0 (count (events-pendings))))
      (t/is (some? (:dirty-at (usage-row team)))))))

(t/deftest events-append-queues-the-whole-stream
  ;; The queue of the events consumer is not selective: the selectivity
  ;; lives in the reaction, which only claims the names it knows.
  (with-redefs [cf/flags test-flags]
    (append-row "upload-file-media-object" {:team-id (uuid/next)})
    (append-row "navigate" {:route "dashboard"})
    (t/is (= 2 (count (events-pendings))))))

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
      (th/run-task! :audit-log-consumer-events {})
      (let [row (usage-row team-id)]
        (t/is (some? row))
        (t/is (some? (:dirty-at row))))
      (t/is (= 1 (th/run-task! :schedule-team-storage {})))
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
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 2 (th/run-task! :schedule-team-storage {})))
      (t/is (= 0 (th/run-task! :schedule-team-storage {})))
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
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 12 (th/run-task! :schedule-team-storage {})))
      (let [rows (th/db-exec! ["SELECT (round((extract(epoch from (scheduled_at - created_at)) * 1000)::numeric, -3))::bigint AS delay_ms FROM job WHERE name = 'recompute-team-storage'"])]
        (t/is (= 12 (count rows)))
        (t/is (= {0 5, 5000 5, 10000 2} (frequencies (map :delay-ms rows))))))))

(t/deftest storage-usage-scheduler-partial-progress-survives-mid-run-failure
  ;; Chunks commit independently: a failure mid-run keeps the chunks
  ;; already enqueued, and the next tick only submits the rest
  ;; (the live ones are skipped).
  (let [teams (mapv #(:default-team-id (th/create-profile* %)) (range 120 132))]
    (with-redefs [cf/flags test-flags
                  team-storage/scheduler-chunk-size 5]
      (doseq [team teams]
        (append-row "upload-file-media-object" {:team-id team}))
      (th/run-task! :audit-log-consumer-events {})
      (let [calls (atom 0)
            orig  jobs/submit]
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (with-redefs [jobs/submit (fn [cfg opts]
                                                   (when (> (swap! calls inc) 7)
                                                     (throw (ex-info "boom" {})))
                                                   (orig cfg opts))]
                         (th/run-task! :schedule-team-storage {}))))
        ;; The first chunk committed before the failure; the second
        ;; rolled back whole.
        (t/is (= 5 (count (th/db-query :job {:name "recompute-team-storage"}))))
        ;; The next tick resumes: 5 live skipped, 7 fresh.
        (t/is (= 7 (th/run-task! :schedule-team-storage {})))
        (t/is (= 12 (count (th/db-query :job {:name "recompute-team-storage"}))))))))

(t/deftest audit-submit-does-not-swallow-failures
  ;; A lost event is a stale consumer: the failure has to surface, or
  ;; the counter drifts with no trace. (An event without a name fails
  ;; validation, which is enough to prove there is no catch.)
  ;; (the fixture stubs `submit`; this one is the real fn)
  (t/is (thrown? Exception
                 (real-audit-submit th/*system* {:type "action"}))))

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
      (t/is (= 1 (th/run-task! :schedule-team-storage {})))
      (let [rows (th/db-exec! ["SELECT label FROM job WHERE name = 'recompute-team-storage'"])]
        (t/is (= [(str stale)] (mapv :label rows)))))))

(t/deftest storage-usage-scheduler-enqueues-on-the-storage-queue
  ;; The recompute runs the slow per-team SUM, so it owns a queue:
  ;; it must never share the default runner with the delete cascades.
  (let [prof (th/create-profile* 75 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (append-row "upload-file-media-object" {:team-id team})
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 1 (th/run-task! :schedule-team-storage {})))
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
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 1 (th/run-task! :schedule-team-storage {})))
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
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 1 (th/run-task! :schedule-team-storage {})))
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
      (t/is (= 1 (th/run-task! :schedule-team-storage {})))
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
      (th/run-task! :audit-log-consumer-events {})
      ;; The drain completed instead of aborting the chunk in a loop:
      ;; no pendings left and the surviving team is marked.
      (t/is (= 0 (count (events-pendings))))
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
      (append-row "upload-file-media-object" {:team-id team :file-id (:id file)})
      (th/run-task! :audit-log-consumer-events {})
      (th/run-task! :schedule-team-storage {})
      (th/run-pending-jobs)
      (let [live (quotes/get-live-media-storage-bytes th/*system* team)]
        (t/is (pos? live))
        (t/is (== live (:total-bytes (usage-row team)))))
      (with-redefs [sys/system th/*system*]
        (srepl/delete-team! (str team)))
      (th/run-task! :audit-log-consumer-events {})
      (th/run-task! :schedule-team-storage {})
      (th/run-pending-jobs)
      (t/is (== 0 (:total-bytes (usage-row team))))
      (with-redefs [sys/system th/*system*]
        (srepl/restore-team! (str team)))
      (t/is (= 1 (count (events-pendings))))
      (th/run-task! :audit-log-consumer-events {})
      (t/is (some? (:dirty-at (usage-row team))))
      (th/run-task! :schedule-team-storage {})
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
      (th/run-task! :audit-log-consumer-events {})
      (th/run-task! :schedule-team-storage {})
      (th/run-pending-jobs)
      (with-redefs [sys/system th/*system*]
        (srepl/restore-file! (str (:id file))))
      (t/is (= 1 (count (events-pendings))))
      (th/run-task! :audit-log-consumer-events {})
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
      (th/run-task! :audit-log-consumer-events {})
      (th/run-task! :schedule-team-storage {})
      (th/run-pending-jobs)
      (with-redefs [sys/system th/*system*]
        (srepl/restore-project! (str (:id proj))))
      (t/is (= 1 (count (events-pendings))))
      (th/run-task! :audit-log-consumer-events {})
      (t/is (some? (:dirty-at (usage-row team)))))))

(t/deftest storage-usage-restore-profile-marks-dirty
  (let [prof (th/create-profile* 64 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags test-flags]
      (with-redefs [sys/system th/*system*]
        (srepl/delete-profile! (str (:id prof))))
      (th/run-task! :audit-log-consumer-events {})
      (th/run-task! :schedule-team-storage {})
      (th/run-pending-jobs)
      (with-redefs [sys/system th/*system*]
        (srepl/restore-profile! (str (:id prof))))
      (t/is (= 1 (count (events-pendings))))
      (th/run-task! :audit-log-consumer-events {})
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
      (th/run-task! :schedule-team-storage {})
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
        (th/run-task! :audit-log-consumer-events {})
        (t/is (some? (:dirty-at (usage-row team-a))))
        (t/is (some? (:dirty-at (usage-row team-b))))
        (th/run-task! :schedule-team-storage {})
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
      (th/run-task! :schedule-team-storage {})
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
        (th/run-task! :audit-log-consumer-events {})
        (t/is (some? (:dirty-at (usage-row team-a))))
        (t/is (some? (:dirty-at (usage-row team-b))))
        (th/run-task! :schedule-team-storage {})
        (th/run-pending-jobs)
        (t/is (== 0 (:total-bytes (usage-row team-a))))
        (let [live-b (quotes/get-live-media-storage-bytes th/*system* team-b)]
          (t/is (pos? live-b))
          (t/is (== live-b (:total-bytes (usage-row team-b)))))))))

(t/deftest storage-usage-bulk-ingest-queues-nothing
  ;; Frontend bulk ingest can never produce a storage-usage event:
  ;; pin that it queues nothing for consumer 3.
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log :storage-usage}]
      (@#'cmd-audit/handle-events
       {::db/pool th/*pool* ::mtx/metrics (th/metrics-stub)}
       (with-meta {::rpc/profile-id (:id prof)
                   ::rpc/request-at (ct/now)
                   :events [{:name "navigate" :type "action"
                             :props {:route "dashboard"}
                             :timestamp (ct/now)}]}
         {:app.http/request (th/make-dummy-request)}))
      (t/is (= 0 (count (events-pendings)))))))

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
      (t/is (= 1 (th/run-task! :schedule-team-storage {})))
      (th/run-pending-jobs)
      (let [row  (usage-row team-id)
            live (quotes/get-live-media-storage-bytes th/*system* team-id)]
        (t/is (nil? (:dirty-at row)))
        (t/is (== live (:total-bytes row)))))))

(t/deftest storage-usage-file-gc-stays-silent-without-trims
  ;; The event is gated on actually trimming media: without that, a
  ;; cron walk over every file would flood the audit buffer with one
  ;; row plus one pending per file it touches.
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
      (th/run-task! :audit-log-consumer-events {})
      (t/is (some? (:dirty-at (usage-row team-id))))
      (t/is (= 1 (th/run-task! :schedule-team-storage {})))
      (th/run-pending-jobs)
      (t/is (nil? (:dirty-at (usage-row team-id))))
      (append-row "delete-object-with-cascade" {:cascade {:team-ids [team-id]}})
      (th/run-task! :audit-log-consumer-events {})
      (t/is (some? (:dirty-at (usage-row team-id)))))))
