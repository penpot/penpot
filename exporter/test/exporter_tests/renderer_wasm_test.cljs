;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.renderer-wasm-test
  "The wasm renderer driver: leased workers, sequential renders,
  failure isolation and driver-owned cancel, against a stub pool,
  stub workers and an injected zero-arg check."
  (:require
   ["node:events" :as events]
   [app.common.transit :as transit]
   [cljs.test :as t :include-macros true]
   [exporter.renderer.wasm :as render]))

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

(defn- stub-pool
  [calls worker]
  #js {:acquire (fn [] (js/Promise.resolve worker))
       :release (fn [_] (swap! calls conj :release) (js/Promise.resolve nil))
       :destroy (fn [_] (swap! calls conj :destroy) (js/Promise.resolve nil))})

(defn- stub-check
  [calls cancelled?]
  (fn []
    (swap! calls conj :check)
    (when @cancelled?
      (throw (ex-info "export job was cancelled"
                      {:type :internal
                       :code :job-cancelled
                       :hint "export job was cancelled"})))))

(defn- test-sys
  [calls worker]
  {:wasm-pool {:pool (stub-pool calls worker) :timeout-ms 5000}})

(defn- test-params
  []
  {:type    :png
   :objects [{:id "o1"}]})

(defn- posted?
  [calls]
  (some #(and (vector? %) (= :post (first %))) calls))

(t/deftest ^:async with-scope-runs-renders-on-the-leased-worker
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1" :path "/tmp/o1.png"}))
          seen   (atom [])]
      (await (render/with-scope (test-sys calls worker)
               {:check-cancelled (stub-check calls (atom false))
                :on-scope (^:async fn [render*]
                            (await (render* (test-params)
                                            (fn [object] (swap! seen conj object) nil)))
                            (await (render* (test-params)
                                            (fn [object] (swap! seen conj object) nil))))}))
      (t/is (= [{:id "o1" :path "/tmp/o1.png"}
                {:id "o1" :path "/tmp/o1.png"}]
               @seen))
      (t/is (= 1 (count (filter #{:release} @calls))))
      (t/testing "the check runs as each render's turn comes up"
        (t/is (= 2 (count (filter #{:check} @calls)))))
      (t/testing "a quiet lease leaves its worker alone"
        (t/is (not-any? #{:terminate} @calls))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async cancelled-render-rejects-before-posting
  (try
    (let [calls (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))]
      (try
        (await (render/with-scope (test-sys calls worker)
                 {:check-cancelled (stub-check calls (atom true))
                  :on-scope (fn [render*]
                              (render* (test-params) (fn [_] nil)))}))
        (t/is false "with-scope should have rejected a cancelled render")
        (catch :default cause
          (t/is (= :job-cancelled (-> cause ex-data :code)))
          (t/is (not (posted? @calls))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async cancel-aborts-the-leased-worker-mid-render
  (try
    (let [calls     (atom [])
          worker    (stub-worker calls (fn [_ _]))
          cancelled (atom false)]
      (try
        (await (render/with-scope (test-sys calls worker)
                 {:check-cancelled (stub-check calls cancelled)
                  :watch-interval 20
                  :on-scope (fn [render*]
                              ;; the cancel lands mid-render, once
                              ;; the worker is listening
                              (js/setTimeout #(reset! cancelled true) 10)
                              (render* (test-params) (fn [_] nil)))}))
        (t/is false "with-scope should have rejected an aborted render")
        (catch :default cause
          (t/is (= :worker-exited (-> cause ex-data :code)))
          (t/is (some #{:terminate} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-leases-and-releases-for-one-render
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))
          seen   (atom [])]
      (await (render/render (test-sys calls worker)
                            (test-params)
                            (fn [object] (swap! seen conj object) nil)
                            (stub-check calls (atom false))))
      (t/is (= [{:id "o1"}] @seen))
      (t/is (= 1 (count (filter #{:release} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-without-check-wires-no-cancel
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))
          seen   (atom [])]
      (await (render/render (test-sys calls worker)
                            (test-params)
                            (fn [object] (swap! seen conj object) nil)
                            nil))
      (t/is (= [{:id "o1"}] @seen))
      (t/is (not-any? #{:terminate} @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async failed-render-does-not-break-the-chain
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))
          seen   (atom [])]
      (await (render/with-scope (test-sys calls worker)
               {:check-cancelled (stub-check calls (atom false))
                :on-scope (^:async fn [render*]
                            (try
                              (await (render* (test-params)
                                              (fn [_] (throw (ex-info "on-object broke" {})))))
                              (catch :default _cause
                                (swap! calls conj :first-failed)))
                            (await (render* (test-params)
                                            (fn [object] (swap! seen conj object) nil))))}))
      (t/is (some #{:first-failed} @calls))
      (t/is (= [{:id "o1"}] @seen)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
