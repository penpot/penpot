;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.worker-dispatcher-test
  (:require
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.metrics :as-alias mtx]
   [app.redis :as rds]
   [app.worker :as-alias wrk]
   [app.worker.dispatcher :as wdisp]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-cfg
  []
  {::db/pool     th/*pool*
   ::rds/client  (get th/*system* :app.redis/client)
   ::mtx/metrics (get th/*system* :app.metrics/metrics)
   ::wrk/tenant  (cf/get :tenant)
   ::batch-size  100
   ::lease       (cf/get-jobs-lease)
   ::timeout     (ct/duration "10s")})

(defn- mk-job!
  [{:keys [name queue status scheduled-at modified-at]
    :or   {name "test-job"
           queue (str (cf/get :tenant) ":test")
           status "new"
           scheduled-at (ct/now)
           modified-at (ct/now)}}]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id            id
                         :name          name
                         :queue         queue
                         :props         (db/json {})
                         :priority      100
                         :max-retries   3
                         :retry-num     0
                         :status        status
                         :scheduled-at  scheduled-at
                         :created-at    (ct/now)
                         :modified-at   modified-at})
    id))

(defn- get-row
  [id]
  (-> (th/db-get :job {:id id} :status :modified-at :scheduled-at :error)
      (update :error #(cond-> % (db/pgobject? %) db/decode-json-pgobject))))

(defn- queue-key
  [queue-name]
  (str "penpot.worker.queue:" (cf/get :tenant) ":" queue-name))

(defn- test-fixture [next]
  (th/database-reset
   (fn []
     ;; clear the redis hand-off list so tests don't see stale payloads
     (let [conn (rds/connect (mk-cfg))]
       (try
         (rds/del conn (queue-key "test"))
         (finally
           (rds/close conn)))
       (next)))))

(t/use-fixtures :each test-fixture)

(defn- drain-queue!
  [queue-name]
  (let [conn (rds/connect (mk-cfg))]
    (try
      (let [cmd (.-cmd conn)
            res (.lrange ^io.lettuce.core.api.sync.RedisCommands
                 cmd (queue-key queue-name) 0 -1)]
        (vec res))
      (finally
        (rds/del conn (queue-key queue-name))
        (rds/close conn)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest dispatcher-claims-due-jobs-and-pushes-json-payload
  (let [cfg  (mk-cfg)
        id   (mk-job! {})
        _    (wdisp/run-batch! cfg)
        row  (get-row id)
        key  (queue-key "test")]

    (t/testing "claimed job is marked scheduled"
      (t/is (= "scheduled" (:status row))))

    (t/testing "payload is plain JSON [uuid, iso-8601]"
      (let [payloads (drain-queue! "test")]
        (t/is (= 1 (count payloads)))
        (let [[job-id scheduled-at :as payload] (json/decode (first payloads))]
          (t/is (string? job-id))
          (t/is (= (str id) job-id))
          (t/is (string? scheduled-at))
          (t/testing "round-trip: inst-ms are equal after ct/inst"
            (let [expected  (:scheduled-at row)
                  actual    (ct/inst scheduled-at)]
              (t/is (= (inst-ms expected) (inst-ms actual))))))))))

(t/deftest dispatcher-claims-retry-jobs-too
  (let [cfg (mk-cfg)
        id  (mk-job! {:status "retry"})]
    (wdisp/run-batch! cfg)
    (t/is (= "scheduled" (:status (get-row id))))))

(t/deftest dispatcher-does-not-claim-future-or-terminal-jobs
  (let [cfg     (mk-cfg)
        future  (mk-job! {:scheduled-at (ct/plus (ct/now)
                                                 (ct/duration {:minutes 10}))})
        running (mk-job! {:status "running"})]
    (wdisp/run-batch! cfg)
    (t/is (= "new" (:status (get-row future))))
    (t/is (= "running" (:status (get-row running))))))

(t/deftest dispatcher-reschedules-lost-scheduled-jobs
  (let [cfg      (mk-cfg)
        lost-id  (mk-job! {:status     "scheduled"
                           :scheduled-at (ct/minus (ct/now)
                                                   (ct/duration {:minutes 6}))})
        fresh-id (mk-job! {:status "scheduled"})]
    (wdisp/run-batch! cfg)
    (let [lost  (get-row lost-id)
          fresh (get-row fresh-id)]
      ;; the lost job is rescheduled to 'new' and claimed again in the
      ;; same batch, so it ends as 'scheduled' with a recent scheduled_at
      (t/is (= "scheduled" (:status lost)))
      (t/is (> (inst-ms (:scheduled-at lost))
               (inst-ms (ct/minus (ct/now)
                                  (ct/duration {:minutes 5})))))
      (t/testing "the rescheduled job was pushed again to the queue"
        (t/is (= 1 (count (drain-queue! "test"))))
        (t/is (= "scheduled" (:status fresh)))))))

(t/deftest dispatcher-marks-stale-running-jobs-as-orphan-by-lease
  (let [cfg    (mk-cfg)
        stale  (mk-job! {:status     "running"
                         :modified-at (ct/minus (ct/now)
                                                (ct/plus (cf/get-jobs-lease)
                                                         (ct/duration {:minutes 1})))})
        fresh  (mk-job! {:status "running"})]
    (wdisp/run-batch! cfg)
    (let [stale-row (get-row stale)]
      (t/is (= "failed" (:status stale-row)))
      (t/is (= {:code "orphan"} (:error stale-row))))

    (t/testing "recent running job is not touched"
      (t/is (= "running" (:status (get-row fresh)))))))

(t/deftest dispatcher-batch-without-pending-jobs-signals-wait
  (let [cfg (mk-cfg)]
    (t/is (= ::wdisp/wait (wdisp/run-batch! cfg)))))
