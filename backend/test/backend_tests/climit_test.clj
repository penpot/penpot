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

(defn- invoke!
  "Run `f` under the limits the cfg declares, the way a command does: the key
  of each limit comes from the params, and the root limiters come after the
  ones the cfg declares."
  [cfg f params]
  (let [wrapped (climit/wrap cfg
                             f
                             {::sv/name "test-command"
                              ::climit/id (::climit/id cfg)})]
    (wrapped cfg params)))

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

(t/deftest acquires-the-command-own-limits-before-the-root-limits
  ;; A command declaring its own limits must meet them before the root
  ;; limiters. No command enters climit from inside its handler any more, so
  ;; every path takes the limiters in the same order.
  (record-metrics
   (fn []
     (th/with-climit [climit test-config]
       (let [mdata   {::sv/name "test-command" ::climit/id image-limits}
             wrapped (climit/wrap {::rpc/climit climit} (fn [_ _] :ok) mdata)
             result  (wrapped {::rpc/climit climit} {::rpc/profile-id (uuid/next)})]
         (t/is (= :ok result))
         (t/is (= ["process-image/by-profile"
                   "process-image/global"
                   "root/by-profile"
                   "root/global"]
                  (acquired-in-order))))))))

(t/deftest the-key-of-a-limit-comes-from-the-params
  ;; The key of a limit is a function of the params: a keyword is looked up in
  ;; them, and that is what tells one profile from another. Two calls with the
  ;; same key share the limiter, and two calls with a different key do not.
  (th/with-climit [climit {:test-key/by-profile {:permits 1 :timeout 2000}}]
    (let [pid     (uuid/next)
          limits  [[:test-key/by-profile ::rpc/profile-id]]
          cfg     {::rpc/climit climit
                   ::climit/id limits}
          gate    (promise)
          entered (promise)
          wrapped (climit/wrap cfg
                               (fn [_ params]
                                 ;; only the call of the first profile waits
                                 (when (= pid (::rpc/profile-id params))
                                   (deliver entered true)
                                   @gate)
                                 :ok)
                               {::sv/name "test-command" ::climit/id limits})
          holder  (future (wrapped cfg {::rpc/profile-id pid}))]

      ;; The call of the same profile waits for the permit this thread holds.
      (t/is (true? (deref entered 5000 false)))
      (t/is (= :timeout (deref (future (wrapped cfg {::rpc/profile-id pid})) 300 :timeout)))

      ;; The call of another profile takes the permit of its own key.
      (t/is (= :ok (deref (future (wrapped cfg {::rpc/profile-id (uuid/next)})) 5000 :timeout)))

      (deliver gate true)
      (t/is (= :ok (deref holder 5000 :timeout))))))

(t/deftest a-command-without-limits-takes-the-root-limits
  ;; A command that declares no limits of its own still takes the root ones,
  ;; and in the same order as the ones of a command that declares them.
  (record-metrics
   (fn []
     (th/with-climit [climit test-config]
       (let [mdata   {::sv/name "test-command"}
             wrapped (climit/wrap {::rpc/climit climit} (fn [_ _] :ok) mdata)
             result  (wrapped {::rpc/climit climit} {::rpc/profile-id (uuid/next)})]
         (t/is (= :ok result))
         (t/is (= ["root/by-profile"
                   "root/global"]
                  (acquired-in-order))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHIPPED CONFIG
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest example-config-bounds-every-wait
  ;; A limiter without `:timeout` parks the calling thread forever, which is
  ;; how a limit nobody releases turns into lost worker threads and days of
  ;; silent 429s. Every entry of the shipped config must carry one. A limiter
  ;; without `:queue` lets its waiters pile up without bound, and a command
  ;; takes its own limits before the root ones, so the queue of a limit is
  ;; where its own pile-up waits.
  (let [path (io/resource "climit.edn")]
    (t/is (some? path))
    (let [data (edn/read-string (slurp path))]
      (t/is (seq data))
      (doseq [[id config] data]
        (t/is (int? (:timeout config))
              (str id " must declare a timeout"))
        (t/is (int? (:queue config))
              (str id " must declare a queue"))))))

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
             holder  (future (invoke! (limiter-cfg climit :test-waiting/by-profile "a")
                                      (fn [_ _] (deliver entered true) @gate :held)
                                      {}))]

         (t/is (true? (deref entered 5000 false)))

         ;; the holder is running, not waiting
         (reset! calls [])
         (let [waiter (future (invoke! (limiter-cfg climit :test-waiting/by-profile "a")
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
             gates {"a" (promise) "b" (promise)}
             holders
             (into {}
                   (map (fn [k]
                          (let [entered (promise)]
                            [k {:entered entered
                                :future  (future (invoke! (limiter-cfg climit :test-waiting-keys/by-profile k)
                                                          (fn [_ _]
                                                            (deliver entered true)
                                                            @(gates k)
                                                            :held)
                                                          {}))}])))
                   ["a" "b"])]

         (doseq [k ["a" "b"]]
           (t/is (true? (deref (:entered (holders k)) 5000 false))))

         ;; one call per key, waiting behind its own holder
         (let [waiters (into {}
                             (map (fn [k]
                                    [k (future (invoke! (limiter-cfg climit :test-waiting-keys/by-profile k)
                                                        (fn [_ _] :waited)
                                                        {}))]))
                             ["a" "b"])]

           (t/is (true? (wait-until #(= 2 (waiting-count climit name)) 5000)))
           ;; the increment that reaches 2 always publishes 2, whichever waiter
           ;; does it last: the order of two concurrent publications is not the
           ;; contract, the value is.
           (t/is (some #(= 2 %) (waiting-value-of name)))

           (doseq [k ["a" "b"]] (deliver (gates k) true))

           (doseq [k ["a" "b"]]
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
             holder  (future (invoke! cfg
                                      (fn [_ _] (deliver entered true) @gate :held)
                                      {}))]

         (t/is (true? (deref entered 5000 false)))
         (reset! calls [])

         ;; the queue holds one waiter, so the next call is rejected right away
         (let [queued (future (failed-type #(invoke! cfg (fn [_ _] :queued) {})))]
           (t/is (true? (wait-until #(= 1 (waiting-last name)) 5000)))
           (t/is (= :concurrency-limit (failed-type #(invoke! cfg (fn [_ _] :extra) {}))))
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
             holder  (future (invoke! cfg
                                      (fn [_ _] (deliver entered true) @gate :held)
                                      {}))]

         (t/is (true? (deref entered 5000 false)))
         (reset! calls [])
         (t/is (= :concurrency-limit (failed-type #(invoke! cfg (fn [_ _] :late) {}))))
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
             holder  (future (invoke! cfg
                                      (fn [_ _] (deliver entered true) @gate :held)
                                      {}))]

         (t/is (true? (deref entered 5000 false)))
         (reset! calls [])

         (t/is (= :concurrency-limit (failed-type #(invoke! cfg (fn [_ _] :extra) {}))))
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
                        (invoke! cfg (fn [_ _] (throw (ex-info "boom" {}))) {})))

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
                       (invoke! cfg (fn [_ _] :never) {}))))

      ;; the count came back down instead of staying up for good
      (t/is (= 0 (waiting-count climit name)))
      (t/is (= [0] (waiting-value-of name)))

      ;; and the thread dropped the mark: with it left behind, this call would
      ;; run inline instead of waiting for the permit another thread holds.
      (let [entered (promise)
            gate    (promise)
            holder  (future (invoke! cfg
                                     (fn [_ _] (deliver entered true) @gate :held)
                                     {}))]
        (t/is (true? (deref entered 5000 false)))
        (t/is (= :concurrency-limit (failed-type #(invoke! cfg (fn [_ _] :inline) {}))))
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
            holder  (future (invoke! cfg
                                     (fn [_ _] (deliver entered true) @gate :held)
                                     {}))]

        (t/is (true? (deref entered 5000 false)))

        (let [waiter (future (invoke! cfg (fn [_ _] :waited) {}))]
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
                 holder  (future (invoke! cfg
                                          (fn [_ _] (deliver entered true) @gate :held)
                                          {}))]

             (t/is (true? (deref entered 5000 false)))
             (reset! calls [])

             (let [q1 (future (invoke! cfg (fn [_ _] :q1) {}))
                   q2 (future (invoke! cfg (fn [_ _] :q2) {}))]

               ;; the two of them fill the queue, so the next one is rejected
               (t/is (true? (wait-until #(= 2 (waiting-count climit name)) 5000)))
               (t/is (some #(= 2 %) (waiting-value-of name)))
               (t/is (= :concurrency-limit (failed-type #(invoke! cfg (fn [_ _] :x) {}))))

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
                 holder  (future (invoke! cfg
                                          (fn [_ _] (deliver entered true) @gate :held)
                                          {}))]

             (t/is (true? (deref entered 5000 false)))

             (with-redefs [climit/log capture]
               (t/is (= :concurrency-limit (failed-type #(invoke! cfg (fn [_ _] :late) {}))))
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
