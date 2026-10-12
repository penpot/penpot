;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.climit-test
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.util.services :as-alias sv]
   [backend-tests.helpers :as th]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :as t]))

(def ^:private !calls (atom []))

(defn- record-metrics!
  "Run `f` recording every metrics call it makes."
  [f]
  (reset! !calls [])
  (with-redefs [mtx/run!
                (fn [_ & {:keys [id labels]}]
                  (swap! !calls conj {:id id :labels (vec labels)}))]
    (f)))

(defn- acquired-in-order
  "Limiter names acquired so far, in acquisition order."
  []
  (->> @!calls
       (filter #(= :rpc-climit-timing (:id %)))
       (mapcat :labels)
       (vec)))

(defn- saw-call?
  "Wait until a metrics call mentions `label`."
  [label timeout]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (cond
        (some (fn [call] (some #{label} (:labels call))) @!calls)
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
  (record-metrics!
   (fn []
     (let [climit  (th/init-climit test-config)
           mdata   {::sv/name "test-command" ::climit/id image-limits}
           wrapped (climit/wrap {::rpc/climit climit} (fn [_ _] :ok) mdata)
           result  (wrapped {::rpc/climit climit} {::rpc/profile-id (uuid/next)})]
       (t/is (= :ok result))
       (t/is (= ["root/by-profile"
                 "root/global"
                 "process-image/by-profile"
                 "process-image/global"]
                (acquired-in-order)))))))

(t/deftest nested-limits-do-not-deadlock-with-the-command-own-limits
  ;; Two paths over one profile: a command declaring its own limits (like
  ;; `upload-file-media-object`) and a command that takes the root limiters and
  ;; then asks for the same limits from inside its handler (like
  ;; `create-file-media-object-from-url`). Each path holds what the other one
  ;; waits for the moment the order differs, and nothing releases.
  (record-metrics!
   (fn []
     (let [climit  (th/init-climit test-config)
           pid     (uuid/next)
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
         (reset! !calls [])
         (let [fa (future (failed-type #(wrapped-a {::rpc/climit climit}
                                                   {::rpc/profile-id pid})))]
           (t/is (true? (saw-call? "root/by-profile" 5000)))

           ;; Let B ask for the limits A holds: if the order differs, each path
           ;; now waits for a permit the other one holds.
           (deliver gate true)

           (t/is (= :ok (deref fa 15000 :timeout)))
           (t/is (= :nested-ok (deref fb 15000 :timeout)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REENTRANCY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest reentrant-invoke-does-not-consume-a-second-permit
  ;; A handler entering climit for a limit it already holds must not queue
  ;; behind itself: it runs inline and takes no second permit. Another thread
  ;; asking for the same limiter still waits, so the limit stays in place.
  (let [climit      (th/init-climit {:process-image/by-profile
                                     {:permits 1 :timeout 2000}})
        pid         (uuid/next)
        cfg         {::rpc/climit climit
                     ::climit/id [[:process-image/by-profile pid]]}
        inner       (promise)
        other       (promise)
        handler     (fn [cfg params]
                      (deliver inner
                               (failed-type #(climit/invoke! cfg (fn [_ _] :inner-ok) params)))
                      (deliver other (future (climit/invoke! cfg (fn [_ _] :other-ok) params)))
                      (t/is (= :waiting (deref @other 300 :waiting)))
                      :outer-ok)

        outcome     (failed-type #(climit/invoke! cfg handler {}))]

    ;; With no reentrancy handling the inner call waits for the permit this very
    ;; thread holds and ends failing by timeout.
    (t/is (= :inner-ok (deref inner 5000 :timeout)))
    (t/is (= :outer-ok outcome))

    ;; once the outer call releases, the other thread gets its permit
    (t/is (= :other-ok (deref @other 5000 :timeout)))))

(t/deftest reentrant-tracking-is-cleaned-up-when-the-handler-fails
  ;; If the mark is not removed, the thread keeps believing it holds the
  ;; limiter and every later call of that key would skip the limit.
  (let [climit  (th/init-climit {:process-image/by-profile
                                 {:permits 1 :timeout 1000}})
        pid     (uuid/next)
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
      (t/is (= :held (deref fb 5000 :timeout))))))

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
  (let [!records (atom [])]
    (add-watch l/log-record ::capture
               (fn [_ _ _ record] (swap! !records conj record)))
    (try
      (th/init-climit {:process-image/by-profile {:permits 1 :timeout 1000}
                       :process-image/global {:permits 8}})

      (t/is (true? (warned? !records 2000)))
      (t/is (= #{:process-image/global} (warned-ids @!records)))

      (finally
        (remove-watch l/log-record ::capture)))))
