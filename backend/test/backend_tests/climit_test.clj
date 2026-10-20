;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.climit-test
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.util.services :as-alias sv]
   [backend-tests.helpers :as th]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [integrant.core :as ig]))

(def ^:private calls (atom []))

(defn- record-metrics
  "Run `f` recording every metrics call it makes."
  [f]
  (reset! calls [])
  (with-redefs [mtx/run!
                (fn [_ & {:keys [id labels val]}]
                  (swap! calls conj {:id id :labels (vec labels) :val val}))]
    (f)))

(defn- acquired-in-order
  "Limiter names acquired so far, in acquisition order."
  []
  (->> @calls
       (filter #(= :rpc-climit-timing (:id %)))
       (mapcat :labels)
       (vec)))

(defn- saw-call?
  "Wait until a metrics call mentions `label`."
  [label timeout]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (cond
        (some (fn [call] (some #{label} (:labels call))) @calls)
        true

        (> (System/currentTimeMillis) deadline)
        false

        :else
        (do (Thread/sleep 10) (recur))))))

(defn- failed-type
  "Run `f` and return its value, or the type of the error it raised."
  [f]
  (try
    (f)
    (catch Throwable cause
      (:type (ex-data cause)))))

(def ^:private no-timeout-hint
  "limiter without timeout, a saturated one parks its threads forever")

(defn- warned-ids
  "Ids of the captured log records that warn about a missing timeout."
  [records]
  (->> records
       (keep (fn [record]
               (when (= no-timeout-hint (get (::l/props record) :hint))
                 (get (::l/props record) :id))))
       (set)))

(defn- warned?
  "Wait until a captured log record warns about a missing timeout."
  [records timeout]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (cond
        (seq (warned-ids @records)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

;; One permit and a bounded wait per limiter: a call either gets its permit, or
;; fails with a concurrency limit error, never parks a thread forever.
(def ^:private test-config
  {:root/global                {:permits 1 :timeout 3000}
   :root/by-profile            {:permits 1 :timeout 3000}
   :process-image/global       {:permits 1 :timeout 3000}
   :process-image/by-profile   {:permits 1 :timeout 3000}})

;; The limits `upload-file-media-object` and `assemble-file-media-object`
;; declare, per profile.
(def ^:private image-limits
  [[:process-image/by-profile ::rpc/profile-id]
   [:process-image/global]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACQUISITION ORDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest acquires-root-limits-before-the-command-own-limits
  ;; A command declaring its own limits must meet the root limiters first.
  ;; The nested `climit/invoke!` calls take only the command's own limits and
  ;; run inside the root ones, so any other order lets two calls of the same
  ;; profile hold the same limiters in opposite order and park for good.
  (record-metrics
   (fn []
     (th/with-climit [climit test-config]
       (let [mdata   {::sv/name "test-command" ::climit/id image-limits}
             wrapped (climit/wrap {::rpc/climit climit} (fn [_ _] :ok) mdata)
             result  (wrapped {::rpc/climit climit} {::rpc/profile-id (uuid/next)})]
         (t/is (= :ok result))
         (t/is (= ["root/by-profile"
                   "root/global"
                   "process-image/by-profile"
                   "process-image/global"]
                  (acquired-in-order))))))))

(t/deftest nested-limits-do-not-deadlock-with-the-command-own-limits
  ;; Two paths over one profile: a command declaring its own limits (like
  ;; `upload-file-media-object`) and a command that takes the root limiters and
  ;; then asks for the same limits from inside its handler (like
  ;; `create-file-media-object-from-url`). Each path holds what the other one
  ;; waits for the moment the order differs, and nothing releases.
  (record-metrics
   (fn []
     (th/with-climit [climit test-config]
       (let [pid     (uuid/next)
             gate    (promise)
             entered (promise)

             handler-b (fn [cfg params]
                         (deliver entered true)
                         @gate
                         (-> cfg
                             (assoc ::climit/id [[:process-image/by-profile pid]
                                                 [:process-image/global]])
                             (assoc ::climit/label "path-b")
                             (climit/invoke! (fn [_ _] :nested-ok) params)))

             wrapped-a (climit/wrap {::rpc/climit climit}
                                    (fn [_ _] :ok)
                                    {::sv/name "path-a" ::climit/id image-limits})

             wrapped-b (climit/wrap {::rpc/climit climit}
                                    handler-b
                                    {::sv/name "path-b"})]

         ;; Path B takes the root limiters and waits inside its handler.
         (let [fb (future (failed-type #(wrapped-b {::rpc/climit climit}
                                                   {::rpc/profile-id pid})))]
           (t/is (true? (deref entered 5000 false)))

           ;; Path A, started now, takes its own limiters and then waits for the
           ;; root ones, which B holds. The recorded metrics are how the test
           ;; knows A got that far instead of guessing with a sleep.
           (reset! calls [])
           (let [fa (future (failed-type #(wrapped-a {::rpc/climit climit}
                                                     {::rpc/profile-id pid})))]
             (t/is (true? (saw-call? "root/by-profile" 5000)))

             ;; Let B ask for the limits A holds: if the order differs, each path
             ;; now waits for a permit the other one holds.
             (deliver gate true)

             (t/is (= :ok (deref fa 15000 :timeout)))
             (t/is (= :nested-ok (deref fb 15000 :timeout))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REENTRANCY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest reentrant-invoke-does-not-consume-a-second-permit
  ;; A handler entering climit for a limit it already holds must not queue
  ;; behind itself: it runs inline and takes no second permit. Another thread
  ;; asking for the same limiter still waits, so the limit stays in place.
  (th/with-climit [climit {:process-image/by-profile
                           {:permits 1 :timeout 2000}}]
    (let [pid     (uuid/next)
          cfg     {::rpc/climit climit
                   ::climit/id [[:process-image/by-profile pid]]}
          inner   (promise)
          other   (promise)
          handler (fn [cfg params]
                    (deliver inner
                             (failed-type #(climit/invoke! cfg (fn [_ _] :inner-ok) params)))
                    (deliver other (future (climit/invoke! cfg (fn [_ _] :other-ok) params)))
                    (t/is (= :waiting (deref @other 300 :waiting)))
                    :outer-ok)

          outcome (failed-type #(climit/invoke! cfg handler {}))]

      ;; With no reentrancy handling the inner call waits for the permit this very
      ;; thread holds and ends failing by timeout.
      (t/is (= :inner-ok (deref inner 5000 :timeout)))
      (t/is (= :outer-ok outcome))

      ;; once the outer call releases, the other thread gets its permit
      (t/is (= :other-ok (deref @other 5000 :timeout))))))

(t/deftest reentrant-tracking-is-cleaned-up-when-the-handler-fails
  ;; If the mark is not removed, the thread keeps believing it holds the
  ;; limiter and every later call of that key would skip the limit.
  (th/with-climit [climit {:process-image/by-profile
                           {:permits 1 :timeout 1000}}]
    (let [pid     (uuid/next)
          cfg     {::rpc/climit climit
                   ::climit/id [[:process-image/by-profile pid]]}
          holding (promise)
          gate    (promise)]

      ;; The failing call runs on this thread, so this thread is the one that
      ;; must forget it held the limiter.
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (climit/invoke! cfg (fn [_ _] (throw (ex-info "boom" {}))) {})))

      ;; another thread takes the only permit
      (let [fb (future (climit/invoke! cfg (fn [_ _]
                                             (deliver holding true)
                                             @gate
                                             :held)
                                       {}))]
        (t/is (true? (deref holding 5000 false)))

        ;; this thread must wait for it, not skip the limiter
        (t/is (= :concurrency-limit
                 (failed-type #(climit/invoke! cfg (fn [_ _] :inner-ok) {}))))

        (deliver gate true)
        (t/is (= :held (deref fb 5000 :timeout)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHIPPED CONFIG
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest example-config-bounds-every-wait
  ;; A limiter without `:timeout` parks the calling thread forever, which is
  ;; how a limit nobody releases turns into lost worker threads and days of
  ;; silent 429s. Every entry of the shipped config must carry one.
  (let [path (io/resource "climit.edn")]
    (t/is (some? path))
    (let [data (edn/read-string (slurp path))]
      (t/is (seq data))
      (doseq [[id config] data]
        (t/is (int? (:timeout config))
              (str id " must declare a timeout"))))))

(t/deftest warns-when-a-limiter-has-no-timeout
  ;; The config that production loads lives outside the repository and is
  ;; maintained by hand, so a limiter without a timeout has to say so at
  ;; startup instead of parking threads in silence months later.
  (let [records (atom [])]
    (add-watch l/log-record ::capture
               (fn [_ _ _ record] (swap! records conj record)))
    (try
      (th/with-climit [_climit {:process-image/by-profile {:permits 1 :timeout 1000}
                                :process-image/global {:permits 8}}]
        (t/is (true? (warned? records 2000)))
        (t/is (= #{:process-image/global} (warned-ids @records))))

      (finally
        (remove-watch l/log-record ::capture)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WAITING COUNTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- waiting-value-of
  "Values published to the waiting gauge for one limiter name, in order."
  [name]
  (->> @calls
       (filter #(= :rpc-climit-waiting (:id %)))
       (filter #(= name (first (:labels %))))
       (map :val)
       (vec)))

(defn- waiting-last
  "Last value published to the waiting gauge for one limiter name. How many
  publications land inside a window is not part of the contract, the value is,
  so this is what the assertions read."
  [name]
  (last (waiting-value-of name)))

(defn- waiting-count
  "Current waiting count of a limiter name, read from its component."
  [climit name]
  (.get ^java.util.concurrent.atomic.AtomicLong
   (#'climit/waiting-counter (:counters climit) name)))

(defn- wait-until
  "Wait until `pred` holds, or give up."
  [pred timeout]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defn- limiter-name
  "The name climit publishes for a limiter id: the keyword without the colon."
  [id]
  (subs (str id) 1))

(defn- limiter-cfg
  [climit name key]
  {::rpc/climit climit
   ::climit/id [[name key]]})

(t/deftest waiting-gauge-follows-the-queue
  ;; The value is the number of calls waiting for a permit: up when a call
  ;; starts waiting, down when the wait ends. The gauges that sampled the
  ;; limiter stats could not show this, because the value of whichever limiter
  ;; of the name ran last was the one that survived.
  (record-metrics
   (fn []
     (th/with-climit [climit {:test-waiting/by-profile {:permits 1 :timeout 3000}}]
       (let [name    (limiter-name :test-waiting/by-profile)
             entered (promise)
             gate    (promise)
             holder  (future (climit/invoke! (limiter-cfg climit :test-waiting/by-profile "a")
                                             (fn [_ _] (deliver entered true) @gate :held)
                                             {}))]

         (t/is (true? (deref entered 5000 false)))

         ;; the holder is running, not waiting
         (reset! calls [])
         (let [waiter (future (climit/invoke! (limiter-cfg climit :test-waiting/by-profile "a")
                                              (fn [_ _] :waited)
                                              {}))]
           (t/is (true? (wait-until #(= 1 (waiting-last name)) 5000)))
           (t/is (= 1 (waiting-count climit name)))

           (deliver gate true)
           (t/is (= :waited (deref waiter 5000 :timeout)))
           (t/is (= :held (deref holder 5000 :timeout)))
           (t/is (true? (wait-until #(= 0 (waiting-last name)) 5000)))
           (t/is (= 0 (waiting-count climit name)))))))))

(t/deftest waiting-gauge-sums-every-key-of-a-name
  ;; Two profiles queueing on the same limiter name add up. That is the shape
  ;; that stayed invisible in production: one series per name, counting the
  ;; entries and the exits, instead of one value per limiter overwritten by the
  ;; next one.
  (record-metrics
   (fn []
     (th/with-climit [climit {:test-waiting-keys/by-profile {:permits 1 :timeout 3000}}]
       (let [name  (limiter-name :test-waiting-keys/by-profile)
             gates {:a (promise) :b (promise)}
             holders
             (into {}
                   (map (fn [k]
                          (let [entered (promise)]
                            [k {:entered entered
                                :future  (future (climit/invoke! (limiter-cfg climit :test-waiting-keys/by-profile k)
                                                                 (fn [_ _]
                                                                   (deliver entered true)
                                                                   @(gates k)
                                                                   :held)
                                                                 {}))}])))
                   [:a :b])]

         (doseq [k [:a :b]]
           (t/is (true? (deref (:entered (holders k)) 5000 false))))

         ;; one call per key, waiting behind its own holder
         (let [waiters (into {}
                             (map (fn [k]
                                    [k (future (climit/invoke! (limiter-cfg climit :test-waiting-keys/by-profile k)
                                                               (fn [_ _] :waited)
                                                               {}))]))
                             [:a :b])]

           (t/is (true? (wait-until #(= 2 (waiting-count climit name)) 5000)))
           ;; the increment that reaches 2 always publishes 2, whichever waiter
           ;; does it last: the order of two concurrent publications is not the
           ;; contract, the value is.
           (t/is (some #(= 2 %) (waiting-value-of name)))

           (doseq [k [:a :b]] (deliver (gates k) true))

           (doseq [k [:a :b]]
             (t/is (= :waited (deref (waiters k) 5000 :timeout)))
             (t/is (= :held (deref (:future (holders k)) 5000 :timeout))))

           (t/is (true? (wait-until #(= 0 (waiting-count climit name)) 5000)))
           (t/is (some #(= 0 %) (waiting-value-of name)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REJECTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rejections
  "Rejections published, as [name reason] pairs."
  []
  (->> @calls
       (filter #(= :rpc-climit-rejected (:id %)))
       (map (fn [call] [(first (:labels call)) (second (:labels call))]))
       (vec)))

(t/deftest rejection-counter-tells-why
  ;; Rejections had no metric: the panel counted them from the logs. Now a call
  ;; rejected because the queue is full and a call rejected because it waited
  ;; too long are counted apart.
  (record-metrics
   (fn []
     (th/with-climit [climit {:test-rejected/by-profile {:permits 1 :queue 1 :timeout 60000}}]
       (let [name    (limiter-name :test-rejected/by-profile)
             cfg     (limiter-cfg climit :test-rejected/by-profile "a")
             entered (promise)
             gate    (promise)
             holder  (future (climit/invoke! cfg
                                             (fn [_ _] (deliver entered true) @gate :held)
                                             {}))]

         (t/is (true? (deref entered 5000 false)))
         (reset! calls [])

         ;; the queue holds one waiter, so the next call is rejected right away
         (let [queued (future (failed-type #(climit/invoke! cfg (fn [_ _] :queued) {})))]
           (t/is (true? (wait-until #(= 1 (waiting-last name)) 5000)))
           (t/is (= :concurrency-limit (failed-type #(climit/invoke! cfg (fn [_ _] :extra) {}))))
           (t/is (= [[name "queue-full"]] (rejections)))

           (deliver gate true)
           (t/is (= :held (deref holder 5000 :timeout)))
           (t/is (= :queued (deref queued 5000 :timeout))))))

     ;; a wait that times out is counted as such, not as a full queue
     (th/with-climit [climit {:test-timeout/by-profile {:permits 1 :timeout 300}}]
       (let [name    (limiter-name :test-timeout/by-profile)
             cfg     (limiter-cfg climit :test-timeout/by-profile "a")
             entered (promise)
             gate    (promise)
             holder  (future (climit/invoke! cfg
                                             (fn [_ _] (deliver entered true) @gate :held)
                                             {}))]

         (t/is (true? (deref entered 5000 false)))
         (reset! calls [])
         (t/is (= :concurrency-limit (failed-type #(climit/invoke! cfg (fn [_ _] :late) {}))))
         (t/is (= [[name "timeout"]] (rejections)))

         (deliver gate true)
         (t/is (= :held (deref holder 5000 :timeout))))))))

(t/deftest the-waiting-count-comes-back-after-a-rejection
  ;; A rejected call increments and decrements exactly once: a limiter that
  ;; rejects under pressure must not drift the panel up.
  (record-metrics
   (fn []
     (th/with-climit [climit {:test-rejected-baseline/by-profile {:permits 1 :queue 0 :timeout 3000}}]
       (let [name    (limiter-name :test-rejected-baseline/by-profile)
             cfg     (limiter-cfg climit :test-rejected-baseline/by-profile "a")
             entered (promise)
             gate    (promise)
             holder  (future (climit/invoke! cfg
                                             (fn [_ _] (deliver entered true) @gate :held)
                                             {}))]

         (t/is (true? (deref entered 5000 false)))
         (reset! calls [])

         (t/is (= :concurrency-limit (failed-type #(climit/invoke! cfg (fn [_ _] :extra) {}))))
         (t/is (= 0 (waiting-count climit name)))
         (t/is (true? (wait-until #(= 0 (waiting-last name)) 5000)))

         (deliver gate true)
         (t/is (= :held (deref holder 5000 :timeout))))))))

(t/deftest the-waiting-count-comes-back-when-the-handler-throws
  ;; A handler that throws still leaves the count where it was.
  (record-metrics
   (fn []
     (th/with-climit [climit {:test-handler-throws/by-profile {:permits 1 :timeout 3000}}]
       (let [name (limiter-name :test-handler-throws/by-profile)
             cfg  (limiter-cfg climit :test-handler-throws/by-profile "a")]

         (reset! calls [])
         (t/is (thrown? clojure.lang.ExceptionInfo
                        (climit/invoke! cfg (fn [_ _] (throw (ex-info "boom" {}))) {})))

         (t/is (= 0 (waiting-count climit name)))
         (t/is (true? (wait-until #(= 0 (waiting-last name)) 5000))))))))

(defn- failing-first-waiting-publish
  "A `mtx/run!` replacement that fails the first waiting publication and records
  the rest, so the decrement of the unwinding path still lands."
  [calls]
  (let [pending (atom true)]
    (fn [_ & {:keys [id labels val]}]
      (if (and @pending (= :rpc-climit-waiting id))
        (do (reset! pending false)
            (throw (ex-info "publishing is down" {})))
        (swap! calls conj {:id id :labels (vec labels) :val val})))))

(t/deftest a-throwable-while-waiting-leaves-no-mark-and-no-count
  ;; `waiting-inc` sits inside the `try`: a throwable from the publishing path
  ;; unwinds through the `finally`, so the thread neither keeps the limiter
  ;; marked as held (it would skip that limiter for that key for ever) nor
  ;; leaves the count up.
  (th/with-climit [climit {:test-throw-waiting/by-profile {:permits 1 :timeout 3000}}]
    (let [name (limiter-name :test-throw-waiting/by-profile)
          cfg  (limiter-cfg climit :test-throw-waiting/by-profile "a")]

      (reset! calls [])
      (with-redefs [mtx/run! (failing-first-waiting-publish calls)]
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (climit/invoke! cfg (fn [_ _] :never) {}))))

      ;; the count came back down instead of staying up for good
      (t/is (= 0 (waiting-count climit name)))
      (t/is (= [0] (waiting-value-of name)))

      ;; and the thread dropped the mark: with it left behind, this call would
      ;; run inline instead of waiting for the permit another thread holds.
      (let [entered (promise)
            gate    (promise)
            holder  (future (climit/invoke! cfg
                                            (fn [_ _] (deliver entered true) @gate :held)
                                            {}))]
        (t/is (true? (deref entered 5000 false)))
        (t/is (= :concurrency-limit (failed-type #(climit/invoke! cfg (fn [_ _] :inline) {}))))
        (deliver gate true)
        (t/is (= :held (deref holder 5000 :timeout)))))))

(defn- gauge-value
  "Current value of the waiting gauge for one limiter name, read from a real
  prometheus collector."
  [metrics name]
  (let [collector (:app.metrics.definition/instance
                   (mtx/get-collector metrics :rpc-climit-waiting))]
    (.get ^io.prometheus.client.Gauge$Child
     (.labels ^io.prometheus.client.Gauge collector (into-array String [name])))))

(t/deftest the-waiting-gauge-reaches-a-real-registry
  ;; The recorded calls prove the arguments, not that a collector with that name
  ;; and those labels exists: a typo in the metric id or a wrong label arity
  ;; would only show up in production. Here the value is read back from a real
  ;; prometheus registry.
  (let [metrics (ig/init-key :app.metrics/metrics {:default main/default-metrics})
        name    (limiter-name :test-registry/by-profile)]

    (th/with-climit [climit {:test-registry/by-profile {:permits 1 :timeout 3000}} metrics]
      (let [cfg     (limiter-cfg climit :test-registry/by-profile "a")
            entered (promise)
            gate    (promise)
            holder  (future (climit/invoke! cfg
                                            (fn [_ _] (deliver entered true) @gate :held)
                                            {}))]

        (t/is (true? (deref entered 5000 false)))

        (let [waiter (future (climit/invoke! cfg (fn [_ _] :waited) {}))]
          (t/is (true? (wait-until #(= 1.0 (gauge-value metrics name)) 5000)))
          (deliver gate true)
          (t/is (= :waited (deref waiter 5000 :timeout)))
          (t/is (= :held (deref holder 5000 :timeout)))
          (t/is (true? (wait-until #(= 0.0 (gauge-value metrics name)) 5000))))))))

(defn- rejected-record
  "The first captured log record that reports a rejection with `reason`."
  [records reason]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [record (first (filter (fn [record]
                                    (let [props (::l/props record)]
                                      (and (= "rejected" (get props :hint))
                                           (= reason (get props :reason)))))
                                  @records))]
        (cond
          record record
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 10) (recur)))))))

(t/deftest rejection-logs-say-why-and-how-big-the-limits-are
  ;; The line has to say whether the queue was full or the wait timed out, and
  ;; how many permits and how much queue the limiter has, or nobody can tell
  ;; whether the limiter did its job.
  (let [records (atom [])]
    (add-watch l/log-record ::capture
               (fn [_ _ _ record] (swap! records conj record)))
    (try
      (record-metrics
       (fn []
         (th/with-climit [climit {:test-log/by-profile {:permits 1 :queue 2 :timeout 60000}}]
           (let [name    (limiter-name :test-log/by-profile)
                 cfg     (limiter-cfg climit :test-log/by-profile "a")
                 entered (promise)
                 gate    (promise)
                 holder  (future (climit/invoke! cfg
                                                 (fn [_ _] (deliver entered true) @gate :held)
                                                 {}))]

             (t/is (true? (deref entered 5000 false)))
             (reset! calls [])

             (let [q1 (future (climit/invoke! cfg (fn [_ _] :q1) {}))
                   q2 (future (climit/invoke! cfg (fn [_ _] :q2) {}))]

               ;; the two of them fill the queue, so the next one is rejected
               (t/is (true? (wait-until #(= 2 (waiting-count climit name)) 5000)))
               (t/is (some #(= 2 %) (waiting-value-of name)))
               (t/is (= :concurrency-limit (failed-type #(climit/invoke! cfg (fn [_ _] :x) {}))))

               (let [props (::l/props (rejected-record records "queue-full"))]
                 (t/is (= 1 (get props :permits)))
                 (t/is (= 2 (get props :queue-max)))
                 (t/is (= :capacity-limit-reached (get props :code)))
                 (t/is (= "queue-full" (get props :reason))))

               (deliver gate true)
               (t/is (= :held (deref holder 5000 :timeout)))
               (deref q1 5000 nil)
               (deref q2 5000 nil))))

         ;; A wait that times out carries its own reason in the line, next to the
         ;; code the limiter raised. The line is emitted at TRACE when nothing
         ;; else is left queued behind it (the caller was the only one waiting),
         ;; so the call to `log` is captured instead of the record, which the
         ;; logger would drop.
         (th/with-climit [climit {:test-log-timeout/by-profile {:permits 1 :timeout 300}}]
           (let [cfg     (limiter-cfg climit :test-log-timeout/by-profile "a")
                 lines   (atom [])
                 entered (promise)
                 gate    (promise)
                 capture (fn [_ action _req-id _stats _elapsed code reason]
                           (swap! lines conj [action code reason]))
                 holder  (future (climit/invoke! cfg
                                                 (fn [_ _] (deliver entered true) @gate :held)
                                                 {}))]

             (t/is (true? (deref entered 5000 false)))

             (with-redefs [climit/log capture]
               (t/is (= :concurrency-limit (failed-type #(climit/invoke! cfg (fn [_ _] :late) {}))))
               (t/is (= [["rejected" :capacity-limit-reached "timeout"]]
                        (filterv #(= "rejected" (first %)) @lines))))

             (deliver gate true)
             (t/is (= :held (deref holder 5000 :timeout)))))))

      (finally
        (remove-watch l/log-record ::capture)))))

(t/deftest republish-writes-the-current-waiting-count
  ;; A call that never comes back keeps the count up, but if it is also the
  ;; last thing that happened nothing rewrites that series and it goes stale in
  ;; the scrape. The republish is what keeps it visible. The counters are built
  ;; on their own: with a component, its sampler would also publish the same
  ;; count the moment its first tick lands inside the window.
  (let [counters (#'climit/create-waiting-counters)
        name     (limiter-name :test-republish/by-profile)
        counter  ^java.util.concurrent.atomic.AtomicLong (#'climit/waiting-counter counters name)]

    (.set counter 3)

    (record-metrics
     (fn []
       (#'climit/republish-waiting (th/metrics-stub) counters)
       (t/is (= [3] (waiting-value-of name)))))))
(t/deftest halting-the-component-stops-the-republisher
  ;; The republisher is a daemon thread started with the component: halting it
  ;; has to stop the thread, or every restart leaves another one running.
  (let [climit  (th/init-climit {:test-halt/by-profile {:permits 1 :timeout 3000}})
        sampler ^java.util.concurrent.ScheduledThreadPoolExecutor (:sampler climit)]

    (t/is (false? (.isShutdown sampler)))

    (ig/halt-key! ::rpc/climit climit)

    (t/is (true? (.isShutdown sampler)))))

(t/deftest the-climit-metrics-that-remain-are-defined
  ;; A typo in a metric id, or a definition that is not there, makes the
  ;; publishing silent, which is the failure mode this work removes. And the
  ;; gauges that could not tell the truth must be gone.
  (let [metrics (ig/init-key :app.metrics/metrics {:default main/default-metrics})]

    (t/is (= ["name"]
             (:app.metrics.definition/labels (mtx/get-collector metrics :rpc-climit-waiting))))
    (t/is (= ["name" "reason"]
             (:app.metrics.definition/labels (mtx/get-collector metrics :rpc-climit-rejected))))
    (t/is (some? (mtx/get-collector metrics :rpc-climit-timing)))

    (t/is (nil? (mtx/get-collector metrics :rpc-climit-queue)))
    (t/is (nil? (mtx/get-collector metrics :rpc-climit-permits)))
    (t/is (nil? (mtx/get-collector metrics :http-worker-busy-threads)))))
