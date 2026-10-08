;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.wasm-pool-service-test
  "The wasm worker pool service: lifecycle through the system, the
  lend protocol, the render protocol, and the creation handshake,
  against fake workers."
  (:require
   ["node:events" :as events]
   [app.common.transit :as transit]
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

(defn- render-behavior
  [payload]
  (fn [^js emitter _msg]
    (js/setTimeout
     (fn []
       (.emit emitter "message" #js {:type "object" :payload (transit/encode-str payload)})
       (.emit emitter "message" #js {:type "done"}))
     0)))

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

(t/deftest ^:async with-worker-lends-and-reclaims
  (try
    (let [calls  (atom [])
          sys    (await (system/init (test-config calls silent-behavior 5000)))
          pool   (pool-of sys)
          seen   (atom nil)
          result (await (pool/with-worker pool (fn [worker] (reset! seen worker) :used)))]
      (t/is (= :used result))
      (t/is (some? (unchecked-get @seen "__id")))
      (t/testing "a second use reuses the returned worker: nothing is created again"
        (await (pool/with-worker pool (fn [_] :again)))
        (t/is (= 1 (count (filter #{:create} @calls)))))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-on-delivers-objects-and-done
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls
                                                 (render-behavior {:id "o1" :path "/tmp/o1.png"})
                                                 5000)))
          seen  (atom [])]
      (t/is (nil? (await (pool/with-worker (pool-of sys)
                           (fn [worker]
                             (pool/render-on worker
                                             {}
                                             (fn [object] (swap! seen conj object))
                                             {:timeout-ms 5000}))))))
      (t/is (= [{:id "o1" :path "/tmp/o1.png"}] @seen))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-on-rejects-cancelled-upfront
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls silent-behavior 5000)))]
      (try
        (await (pool/with-worker (pool-of sys)
                 (fn [worker]
                   (pool/render-on worker
                                   {}
                                   (fn [_])
                                   {:check-cancelled (fn []
                                                       (throw (ex-info "export job was cancelled"
                                                                       {:code :job-cancelled})))
                                    :timeout-ms 5000}))))
        (t/is false "render-on should have rejected a cancelled render")
        (catch :default cause
          (t/is (= :job-cancelled (-> cause ex-data :code)))
          (t/is (not-any? #(and (vector? %) (= :post (first %))) @calls))))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async silent-worker-times-out-and-is-terminated
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls silent-behavior 50)))]
      (try
        (await (pool/with-worker (pool-of sys)
                 (fn [worker]
                   (pool/render-on worker {} (fn [_]) {:timeout-ms 50}))))
        (t/is false "render-on should have rejected a silent worker")
        (catch :default cause
          (t/is (= :render-timeout (-> cause ex-data :code)))
          (t/is (some #{:terminate} @calls))))
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
