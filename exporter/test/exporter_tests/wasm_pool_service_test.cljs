;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.wasm-pool-service-test
  "The wasm worker pool service: lifecycle through the system, the
  lend protocol analogy to a transaction (return on success, destroy
  on failure), and the creation handshake, against fake workers."
  (:require
   ["node:events" :as events]
   [cljs.test :as t :include-macros true]
   [exporter.utils.system :as system]
   [exporter.wasm.pool :as pool]))

(defn- fake-worker
  [calls behavior]
  (let [emitter (events/EventEmitter.)]
    (unchecked-set emitter "postMessage"
                   (fn [msg]
                     (swap! calls conj [:post (unchecked-get msg "type")])
                     (behavior emitter msg)))
    (unchecked-set emitter "terminate"
                   (fn []
                     (swap! calls conj :terminate)
                     (js/Promise.resolve nil)))
    emitter))

(defn- fake-create-worker
  [calls behavior]
  (fn []
    (swap! calls conj :create)
    (js/Promise.resolve (fake-worker calls behavior))))

(defn- silent-behavior
  [_emitter _msg])

(defn- test-config
  [calls behavior timeout-ms]
  {:exporter.wasm.pool/pool {:max           1
                             :min           0
                             :timeout-ms    timeout-ms
                             :create-worker (fake-create-worker calls behavior)}})

(defn- pool-of
  [sys]
  (:pool (:exporter.wasm.pool/pool sys)))

(t/deftest ^:async pool-starts-and-stops-through-the-system
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls silent-behavior 5000)))]
      (t/is (some? (:exporter.wasm.pool/pool sys)))
      (t/is (= 5000 (:timeout-ms (:exporter.wasm.pool/pool sys))))
      (t/is (nil? (await (system/halt sys)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async run-lends-and-reclaims
  (try
    (let [calls  (atom [])
          sys    (await (system/init (test-config calls silent-behavior 5000)))
          pool   (pool-of sys)
          seen   (atom nil)
          result (await (pool/run pool (fn [cfg] (reset! seen cfg) :used)))]
      (t/is (= :used result))
      (t/is (some? (unchecked-get (:exporter.wasm.pool/worker @seen) "__id")))
      (t/testing "a second use reuses the returned worker: nothing is created again"
        (await (pool/run pool (fn [_] :again)))
        (t/is (= 1 (count (filter #{:create} @calls)))))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async run-reads-the-pool-from-cfg
  (try
    (let [calls  (atom [])
          sys    (await (system/init (test-config calls silent-behavior 5000)))
          seen   (atom nil)
          result (await (pool/run sys
                                  (fn [cfg] (reset! seen cfg) :used)))]
      (t/is (= :used result))
      (t/is (some? (unchecked-get (:exporter.wasm.pool/worker @seen) "__id")))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async run-threads-extra-args-to-the-body
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls silent-behavior 5000)))]
      (t/is (= [:a :b] (await (pool/run (pool-of sys)
                                        (fn [_ x y] [x y])
                                        :a :b))))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async run-rejects-cfg-without-pool
  (try
    (try
      (await (pool/run {:timeout-ms 5000} (fn [_] :used)))
      (t/is false "run should have rejected a cfg without pool")
      (catch :default cause
        (t/is (= :invalid-pool-cfg (-> cause ex-data :code)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async run-destroys-the-worker-on-body-failure
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls silent-behavior 5000)))]
      (try
        (await (pool/run (pool-of sys)
                         (fn [_] (throw (ex-info "body broke" {})))))
        (t/is false "run should have rejected a failed body")
        (catch :default cause
          (t/is (= "body broke" (ex-message cause)))
          (t/is (some #{:terminate} @calls))
          (t/is (not-any? #{:release} @calls))))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async creation-handshake-resolves-on-ready
  (try
    (let [worker ^js (events/EventEmitter.)
          ready  (pool/await-ready worker 500)]
      (js/setTimeout #(.emit worker "message" #js {:type "ready"}) 5)
      (t/is (identical? worker (await ready))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async silent-handshake-rejects-and-terminates
  (try
    (let [calls  (atom [])
          worker (fake-worker calls silent-behavior)]
      (try
        (await (pool/await-ready worker 20))
        (t/is false "await-ready should have rejected a silent worker")
        (catch :default cause
          (t/is (= :worker-not-ready (-> cause ex-data :code)))
          (t/is (some #{:terminate} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async errored-handshake-rejects-with-the-cause
  (try
    (let [worker ^js (events/EventEmitter.)
          ready  (pool/await-ready worker 500)]
      (js/setTimeout #(.emit worker "error" (ex-info "boot broke" {})) 5)
      (try
        (await ready)
        (t/is false "await-ready should have rejected a broken worker")
        (catch :default cause
          (t/is (= "boot broke" (ex-message cause))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async terminate-marks-and-kills
  (try
    (let [calls  (atom [])
          worker (fake-worker calls silent-behavior)]
      (t/is (nil? (pool/terminate nil)))
      (pool/terminate worker)
      (t/is (false? (unchecked-get worker "__alive")))
      (t/is (some #{:terminate} @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
