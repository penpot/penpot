;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.queue-test
  "The shared export queue, against a real redis.

  Runs only when `PENPOT_REDIS_URI` is set; each test is skipped otherwise.
  Every test starts from an empty queue and instance registry."
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.handlers.export :as export]
   [app.instance :as instance]
   [app.jobs :as jobs]
   [app.jobs.queue :as queue]
   [app.jobs.store :as store]
   [app.jobs.worker :as worker]
   [app.redis :as redis]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private redis-uri
  (unchecked-get (.-env js/process) "PENPOT_REDIS_URI"))

(t/use-fixtures :once
  {:before (fn [] (when redis-uri (redis/init)))
   :after  (fn [] (when redis-uri (redis/stop)))})

(defn- reset-keys!
  []
  (p/do
    (redis/del! (queue/queue-key))
    (redis/del! (instance/registry-key))
    (redis/del! (queue/processing-key instance/id))))

(defn- run
  "Runs the promise-returning `f` against a clean redis, then calls `done`.
  A rejection fails the test instead of hanging it."
  [done f]
  (if-not redis-uri
    (do (println "    skipped: PENPOT_REDIS_URI not set")
        (done))
    (p/handle (p/do (reset-keys!) (f))
              (fn [_ cause]
                (when cause
                  (t/is (nil? cause) (str "unexpected rejection: " (ex-message cause))))
                (done)
                nil))))

(defn- until
  "Resolves once `pred` holds, polling; rejects after `timeout-ms`."
  [pred timeout-ms]
  (let [deadline (+ (js/Date.now) timeout-ms)]
    (letfn [(step []
              (cond
                (pred)                      (p/resolved true)
                (> (js/Date.now) deadline) (p/rejected (ex-info "condition not met in time" {}))
                :else                      (p/mcat (fn [_] (step)) (p/delay 50))))]
      (step))))

(defn- job-attrs
  []
  {:profile-id (uuid/next)
   :cmd :export-shapes
   :backend "browser"
   :total 1
   :name "test"
   :resource-id (uuid/next)})

(defn- queued-job!
  "A job recorded and put on the queue, as an `api` process leaves it."
  []
  (p/let [job (jobs/create-queued! (job-attrs))
          _   (queue/enqueue! job {:cmd :export-shapes
                                   :params {:file-id (uuid/next) :type :png}
                                   :auth-token "session-token"})]
    job))

(t/deftest claim-takes-the-oldest-job-and-holds-it
  (t/async done
    (run done
         (fn []
           (p/let [job1    (queued-job!)
                   _job2   (queued-job!)
                   claimed (queue/claim! 1)
                   depth   (queue/depth)
                   held    (redis/lrange (queue/processing-key instance/id))]
             (t/is (= (str (:id job1)) claimed))
             (t/is (= 1 depth))
             (t/is (= [(str (:id job1))] held)))))))

(t/deftest claim-resolves-nil-when-the-queue-stays-empty
  (t/async done
    (run done
         (fn []
           (p/let [claimed (queue/claim! 1)]
             (t/is (nil? claimed)))))))

(t/deftest payload-keeps-params-and-seals-the-token
  (t/async done
    (run done
         (fn []
           (p/let [job     (queued-job!)
                   raw     (redis/get-key (queue/payload-key (:id job)))
                   payload (queue/payload (:id job))]
             (t/is (not (str/includes? raw "session-token")))
             (t/is (= "session-token" (:auth-token payload)))
             (t/is (= :png (-> payload :params :type)))
             (t/is (uuid? (-> payload :params :file-id))))))))

(t/deftest ack-releases-the-claim-and-the-payload
  (t/async done
    (run done
         (fn []
           (p/let [job     (queued-job!)
                   claimed (queue/claim! 1)
                   _       (queue/ack! claimed)
                   held    (redis/lrange (queue/processing-key instance/id))
                   payload (queue/payload (:id job))]
             (t/is (= [] held))
             (t/is (nil? payload)))))))

(t/deftest reap-puts-a-dead-instance-jobs-first-in-line
  (t/async done
    (run done
         (fn []
           (let [dead (str "dead-" (uuid/next))]
             (p/let [waiting (queued-job!)
                     lost    (jobs/create-queued! (job-attrs))
                     _       (redis/sadd! (instance/registry-key) dead)
                     _       (redis/rpush! (queue/processing-key dead) (str (:id lost)))
                     moved   (queue/reap!)
                     queued  (redis/lrange (queue/queue-key))
                     members (instance/members)]
               (t/is (= [(str (:id lost))] moved))
               (t/is (= [(str (:id lost)) (str (:id waiting))] queued))
               (t/is (not (contains? (set members) dead)))))))))

(t/deftest reap-leaves-live-instances-alone
  (t/async done
    (run done
         (fn []
           (let [live (str "live-" (uuid/next))]
             (p/let [job   (jobs/create-queued! (job-attrs))
                     _     (redis/sadd! (instance/registry-key) live)
                     _     (redis/set-ex! (instance/heartbeat-key live) "worker" 30)
                     _     (redis/rpush! (queue/processing-key live) (str (:id job)))
                     moved (queue/reap!)
                     held  (redis/lrange (queue/processing-key live))]
               (t/is (= [] moved))
               (t/is (= [(str (:id job))] held))
               (p/do
                 (redis/del! (queue/processing-key live))
                 (redis/del! (instance/heartbeat-key live)))))))))

(t/deftest full-queue-rejects-new-work
  (t/async done
    (run done
         (fn []
           (p/let [_      (p/all (map (fn [_] (redis/rpush! (queue/queue-key) (str (uuid/next))))
                                      (range (cf/get :exporter-queue-max 64))))
                   ;; `p/merr`, not `p/handle`: promesa 12 mishandles a
                   ;; keyword returned from a `handle` callback.
                   result (->> (queue/check-capacity!)
                               (p/fmap (constantly nil))
                               (p/merr (fn [cause] (p/resolved (:code (ex-data cause))))))]
             (t/is (= :queue-full result)))))))

(t/deftest cancelling-a-queued-job-takes-it-off-the-queue
  (t/async done
    (run done
         (fn []
           (p/let [job     (queued-job!)
                   _       (jobs/cancel! (:id job))
                   record  (jobs/fetch (:id job))
                   depth   (queue/depth)
                   payload (queue/payload (:id job))]
             (t/is (= "cancelled" (:state record)))
             (t/is (= 0 depth))
             (t/is (nil? payload)))))))

(t/deftest cancel-sent-before-a-worker-registers-the-job-still-lands
  (t/testing "the flag left by the cancel request is honoured on adopt"
    (t/async done
      (run done
           (fn []
             (p/let [job     (jobs/create-queued! (job-attrs))
                     _       (store/request-cancel! (:id job))
                     _       (jobs/adopt! job (constantly (p/resolved nil)))
                     current (jobs/lookup (:id job))]
               (t/is (= "cancelled" (:state current)))
               (t/is (= instance/id (:owner current)))
               (jobs/release! (:id job))))))))

(t/deftest lost-job-is-requeued-until-it-runs-out-of-attempts
  (t/async done
    (run done
         (fn []
           (p/let [retry  (jobs/create-queued! (job-attrs))
                   spent  (jobs/create-queued! (job-attrs))
                   _      (store/persist! (assoc retry :state "running" :attempts 1))
                   _      (store/persist! (assoc spent :state "running"
                                                 :attempts (cf/get :exporter-max-attempts 3)))
                   _      (redis/rpush! (queue/queue-key) (str (:id retry)))
                   _      (redis/rpush! (queue/queue-key) (str (:id spent)))
                   _      (jobs/requeue! (str (:id retry)))
                   _      (jobs/requeue! (str (:id spent)))
                   retry' (jobs/fetch (:id retry))
                   spent' (jobs/fetch (:id spent))
                   queued (redis/lrange (queue/queue-key))]
             (t/is (= "queued" (:state retry')))
             (t/is (contains? #{"error" "failed"} (:state spent')))
             (t/is (= [(str (:id retry))] queued)))))))

(t/deftest claim-race-two-workers-grab-only-one-wins
  (t/testing "two simulated workers grab in quick succession, only one should win"
    (t/async done
      (run done
           (fn []
             (let [worker-a (str "worker-a-" (uuid/next))
                   worker-b (str "worker-b-" (uuid/next))]
               (p/let [job    (queued-job!)
                       claims (p/all [(redis/blmove! (queue/queue-key) (queue/processing-key worker-a) "LEFT" "RIGHT" 1)
                                      (redis/blmove! (queue/queue-key) (queue/processing-key worker-b) "LEFT" "RIGHT" 1)])
                       held-a (redis/lrange (queue/processing-key worker-a))
                       held-b (redis/lrange (queue/processing-key worker-b))
                       depth  (queue/depth)]
                 (t/is (= 1 (count (remove nil? claims))))
                 (t/is (= [(str (:id job))] (remove nil? claims)))
                 (t/is (or (and (= [(str (:id job))] held-a) (empty? held-b))
                           (and (= [(str (:id job))] held-b) (empty? held-a))))
                 (t/is (= 0 depth))
                 (p/do
                   (redis/del! (queue/processing-key worker-a))
                   (redis/del! (queue/processing-key worker-b))))))))))

(t/deftest retry-after-simulated-failure
  (t/testing "a crashed worker's job returns to queued with attempts tracked"
    (t/async done
      (run done
           (fn []
             (let [failed-worker (str "crashed-" (uuid/next))]
               (p/let [job      (jobs/create-queued! (job-attrs))
                       _        (redis/sadd! (instance/registry-key) failed-worker)
                       _        (redis/rpush! (queue/processing-key failed-worker) (str (:id job)))
                       _        (store/persist! (assoc job :state "processing" :owner failed-worker :attempts 1))
                       moved    (queue/reap!)
                       _        (jobs/requeue! (str (:id job)))
                       requeued (jobs/fetch (:id job))
                       queued   (redis/lrange (queue/queue-key))]
                 (t/is (= [(str (:id job))] moved))
                 (t/is (= "queued" (:state requeued)))
                 (t/is (nil? (:owner requeued)))
                 (t/is (= [(str (:id job))] queued))
                 (t/is (= 1 (:attempts requeued))))))))))

(t/deftest dead-letter-once-max-attempts-hit
  (t/testing "job fails and leaves the queue once attempts hit max"
    (t/async done
      (run done
           (fn []
             (let [max-attempts (cf/get :exporter-max-attempts 3)]
               (p/let [job    (jobs/create-queued! (job-attrs))
                       _      (store/persist! (assoc job :state "processing" :attempts max-attempts))
                       _      (redis/rpush! (queue/queue-key) (str (:id job)))
                       _      (jobs/requeue! (str (:id job)))
                       dead   (jobs/fetch (:id job))
                       queued (redis/lrange (queue/queue-key))]
                 (t/is (contains? #{"failed" "error"} (:state dead)))
                 (t/is (= "export worker lost" (:error dead)))
                 (t/is (= [] queued)))))))))

(t/deftest worker-claims-runs-and-releases-a-job
  (t/testing "a job whose payload cannot be prepared ends in error, not stuck in the queue"
    (t/async done
      (run done
           (fn []
             (p/let [job     (jobs/create-queued! (job-attrs))
                     _       (queue/enqueue! job {:cmd :no-such-command
                                                  :params {}
                                                  :auth-token "session-token"})
                     _       (worker/init)
                     settled (jobs/await-settled (:id job) 10000)
                     _       (until #(zero? (worker/in-flight)) 5000)
                     _       (worker/stop)
                     depth   (queue/depth)
                     held    (redis/lrange (queue/processing-key instance/id))]
               (t/is (contains? #{"error" "failed"} (:state settled)))
               (t/is (= instance/id (:owner settled)))
               (t/is (= 0 depth))
               (t/is (= [] held))))))))

(t/deftest worker-pulls-a-job-runs-it-and-records-the-result
  (t/testing "a claimed job reaches the export step with its payload, ends done, and leaves no claim behind"
    (t/async done
      (run done
           (fn []
             (let [original export/run-claimed!
                   seen     (atom nil)
                   ;; Stands in for the render: same lifecycle calls the real
                   ;; export makes, without needing a browser.
                   stub     (fn [job payload]
                              (reset! seen payload)
                              (p/let [job (jobs/adopt! job (fn [_] nil))
                                      _   (jobs/complete! job {:uri "http://assets/test.png"
                                                               :filename "test.png"
                                                               :mtype "image/png"
                                                               :size 1})]
                                (jobs/release! (:id job))))]
               (set! export/run-claimed! stub)
               (p/finally
                 (p/let [job     (queued-job!)
                         _       (worker/init)
                         settled (jobs/await-settled (:id job) 10000)
                         _       (until #(zero? (worker/in-flight)) 5000)
                         _       (worker/stop)
                         depth   (queue/depth)
                         held    (redis/lrange (queue/processing-key instance/id))
                         payload (queue/payload (:id job))]
                   (t/is (= "done" (:state settled)))
                   (t/is (= "http://assets/test.png" (:resource-uri settled)))
                   (t/is (= instance/id (:owner settled)))
                   (t/is (= 1 (:attempts settled)))
                   (t/is (= :export-shapes (:cmd @seen)))
                   (t/is (= "session-token" (:auth-token @seen)))
                   (t/is (= 0 depth))
                   (t/is (= [] held))
                   (t/is (nil? payload)))
                 (fn []
                   (worker/stop)
                   (set! export/run-claimed! original)))))))))
