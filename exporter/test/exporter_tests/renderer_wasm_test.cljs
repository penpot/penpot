;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.renderer-wasm-test
  "The wasm renderer driver: a single shot over the scope lease,
  against a stub pool, stub workers and a positional zero-arg check."
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
    (swap! calls conj :check-cancelled)
    (when @cancelled?
      (throw (ex-info "export job was cancelled"
                      {:type :internal
                       :code :job-cancelled
                       :hint "export job was cancelled"})))))

(defn- test-cfg
  [calls worker]
  {:exporter.wasm/pool {:pool (stub-pool calls worker) :timeout-ms 5000}})

(defn- test-params
  []
  {:type    :png
   :objects [{:id "o1"}]})

(t/deftest ^:async render-leases-and-releases-for-one-render
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))
          seen   (atom [])]
      (await (render/render (test-cfg calls worker)
                            (test-params)
                            (fn [object] (swap! seen conj object) nil)
                            (stub-check calls (atom false))))
      (t/is (= [{:id "o1"}] @seen))
      (t/is (= 1 (count (filter #{:release} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-without-check-rejects-before-leasing
  (try
    (let [calls  (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))]
      (try
        (await (render/render (test-cfg calls worker)
                              (test-params)
                              (fn [_] nil)
                              nil))
        (t/is false "render should have rejected a missing check")
        (catch :default cause
          (t/is (= :assertion (-> cause ex-data :type)))
          (t/is (= :check-cancelled-missing (-> cause ex-data :code)))
          (t/is (not-any? #{:acquire} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
