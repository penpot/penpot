;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.wasm-scope-test
  "The headless lease: one shared worker, sequential renders, failure
  isolation and driver-owned cancel, against a stub pool and stub
  workers. Task keys ride in cfg under `:exporter.renderer/*`."
  (:require
   ["node:events" :as events]
   [app.common.transit :as transit]
   [cljs.test :as t :include-macros true]
   [exporter.wasm.scope :as scope]))

(defn- stub-worker
  [calls behavior]
  (let [^js emitter (events/EventEmitter.)]
    (unchecked-set emitter "postMessage"
                   (fn [msg]
                     (swap! calls conj [:post (unchecked-get msg "type")])
                     (behavior emitter msg)))
    (unchecked-set emitter "terminate"
                   (fn []
                     (swap! calls conj :terminate)
                     (.emit emitter "exit" 1)
                     (js/Promise.resolve nil)))
    emitter))

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

(defn- stub-pool
  [calls worker]
  #js {:acquire (fn [] (js/Promise.resolve worker))
       :release (fn [_] (swap! calls conj :release) (js/Promise.resolve nil))
       :destroy (fn [_] (swap! calls conj :destroy) (js/Promise.resolve nil))})

(defn- stub-check
  [calls cancelled?]
  (fn []
    (swap! calls conj :check-cancelled)
    (when @cancelled?
      (throw (ex-info "export job was cancelled"
                      {:type :internal
                       :code :job-cancelled
                       :hint "export job was cancelled"})))))

(defn- test-cfg
  ([calls worker on-object cancelled?]
   (test-cfg calls worker on-object cancelled? nil))
  ([calls worker on-object cancelled? watch-interval]
   (cond-> {:exporter.wasm/pool {:pool (stub-pool calls worker) :timeout-ms 5000}
            :exporter.renderer/on-object on-object
            :exporter.renderer/check-cancelled (stub-check calls cancelled?)}
     watch-interval (assoc :exporter.wasm.scope/watch-interval watch-interval))))

(defn- test-params
  []
  {:type    :png
   :objects [{:id "o1"}]})

(defn- posted?
  [calls]
  (some #(and (vector? %) (= :post (first %))) calls))

(t/deftest ^:async run-shares-one-worker-across-renders
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1" :path "/tmp/o1.png"}))
          seen   (atom [])
          cfg    (test-cfg calls worker
                           (fn [object] (swap! seen conj object) nil)
                           (atom false))]
      (await (scope/run cfg
                        (^:async fn [render-fn]
                          (await (render-fn (test-params)))
                          (await (render-fn (test-params))))))
      (t/is (= [{:id "o1" :path "/tmp/o1.png"}
                {:id "o1" :path "/tmp/o1.png"}]
               @seen))
      (t/is (= 1 (count (filter #{:release} @calls))))
      (t/testing "the check runs as each render's turn comes up"
        (t/is (= 2 (count (filter #{:check-cancelled} @calls)))))
      (t/testing "a quiet lease leaves its worker alone"
        (t/is (not-any? #{:terminate} @calls))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async cancelled-render-rejects-before-posting
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))
          cfg    (test-cfg calls worker (fn [_] nil) (atom true))]
      (try
        (await (scope/run cfg (fn [render-fn] (render-fn (test-params)))))
        (t/is false "run should have rejected a cancelled render")
        (catch :default cause
          (t/is (= :job-cancelled (-> cause ex-data :code)))
          (t/is (not (posted? @calls))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async cancel-aborts-the-leased-worker-mid-render
  (try
    (let [calls     (atom [])
          worker    (stub-worker calls silent-behavior)
          cancelled (atom false)
          cfg       (test-cfg calls worker (fn [_] nil) cancelled 20)]
      (try
        (await (scope/run cfg
                          (fn [render-fn]
                            ;; the cancel lands mid-render, once
                            ;; the worker is listening
                            (js/setTimeout #(reset! cancelled true) 10)
                            (render-fn (test-params)))))
        (t/is false "run should have rejected an aborted render")
        (catch :default cause
          (t/is (= :worker-exited (-> cause ex-data :code)))
          (t/is (some #{:terminate} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async failed-render-does-not-break-the-chain
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))
          seen   (atom [])
          thrown (atom false)
          cfg    (test-cfg calls worker
                           (fn [object]
                             (if (compare-and-set! thrown false true)
                               (throw (ex-info "on-object broke" {}))
                               (do (swap! seen conj object) nil)))
                           (atom false))]
      (await (scope/run cfg
                        (^:async fn [render-fn]
                          (try
                            (await (render-fn (test-params)))
                            (catch :default _cause
                              (swap! calls conj :first-failed)))
                          (await (render-fn (test-params))))))
      (t/is (some #{:first-failed} @calls))
      (t/is (= [{:id "o1"}] @seen)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-on-without-lease-raises
  (try
    (try
      (await (scope/render-on {:exporter.renderer/on-object (fn [_] nil)
                               :exporter.renderer/check-cancelled (fn [] nil)
                               :exporter.wasm/pool {:pool nil :timeout-ms 50}}
                              (test-params)))
      (t/is false "render-on should have rejected a missing lease")
      (catch :default cause
        (t/is (= :worker-not-leased (-> cause ex-data :code)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-on-without-timeout-raises
  (try
    (try
      (await (scope/render-on {:exporter.wasm.pool/worker #js {}
                               :exporter.renderer/on-object (fn [_] nil)
                               :exporter.renderer/check-cancelled (fn [] nil)
                               :exporter.wasm/pool {:pool nil}}
                              (test-params)))
      (t/is false "render-on should have rejected a missing timeout")
      (catch :default cause
        (t/is (= :assertion (-> cause ex-data :type)))
        (t/is (= :timeout-ms-missing (-> cause ex-data :code)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async silent-worker-times-out-and-is-terminated
  (try
    (let [calls  (atom [])
          worker (stub-worker calls silent-behavior)
          cfg    (-> (test-cfg calls worker (fn [_] nil) (atom false))
                     (assoc-in [:exporter.wasm/pool :timeout-ms] 50))]
      (try
        (await (scope/run cfg (fn [render-fn] (render-fn (test-params)))))
        (t/is false "run should have rejected a silent worker")
        (catch :default cause
          (t/is (= :render-timeout (-> cause ex-data :code)))
          (t/is (some #{:terminate} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
