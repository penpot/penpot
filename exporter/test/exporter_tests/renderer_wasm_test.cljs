;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.renderer-wasm-test
  "The wasm renderer driver: leased workers, sequential renders,
  failure isolation and cancel wiring, against a stub pool, stub
  workers and an injected cancel source."
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

(defn- stub-cancel-source
  [calls cancelled?]
  {:cancel-signal (fn [job-id] (swap! calls conj [:cancel-signal job-id]) nil)
   :cancelled?    (fn [job-id] (swap! calls conj [:cancelled?-asked job-id]) @cancelled?)
   :on-cancel     (fn [job-id f] (swap! calls conj [:on-cancel job-id f]) nil)})

(defn- test-instance
  [calls worker]
  {:pool       (stub-pool calls worker)
   :timeout-ms 5000})

(defn- test-params
  [job-id]
  {:job-id  job-id
   :type    :png
   :objects [{:id "o1"}]})

(defn- posted?
  [calls]
  (some #(and (vector? %) (= :post (first %))) calls))

(t/deftest ^:async with-scope-runs-renders-on-the-leased-worker
  (try
    (let [calls (atom [])
          worker (stub-worker calls (render-behavior {:id "o1" :path "/tmp/o1.png"}))
          seen (atom [])]
      (await (render/with-scope (test-instance calls worker)
               (stub-cancel-source calls (atom false))
               :job-1
               (^:async fn [render*]
                 (await (render* (test-params :job-1)
                                 (fn [object] (swap! seen conj object) nil)))
                 (await (render* (test-params :job-1)
                                 (fn [object] (swap! seen conj object) nil))))))
      (t/is (= [{:id "o1" :path "/tmp/o1.png"}
                {:id "o1" :path "/tmp/o1.png"}]
               @seen))
      (t/is (= 1 (count (filter #{:release} @calls))))
      (t/testing "the job was wired for cancel exactly once"
        (t/is (= [[:cancel-signal :job-1]
                  [:cancelled?-asked :job-1]
                  [:cancelled?-asked :job-1]]
                 (filter #(and (vector? %) (not (contains? #{:post :on-cancel} (first %))))
                         @calls)))
        (let [registrations (filter #(and (vector? %) (= :on-cancel (first %))) @calls)]
          (t/is (= 1 (count registrations)))
          (t/is (= :job-1 (second (first registrations)))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async cancelled-render-rejects-before-posting
  (try
    (let [calls (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))
          cancelled (atom true)]
      (try
        (await (render/with-scope (test-instance calls worker)
                 (stub-cancel-source calls cancelled)
                 :job-1
                 (fn [render*]
                   (render* (test-params :job-1) (fn [_] nil)))))
        (t/is false "with-scope should have rejected a cancelled render")
        (catch :default cause
          (t/is (= :job-cancelled (-> cause ex-data :code)))
          (t/is (not (posted? @calls))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async cancel-terminates-the-leased-worker
  (try
    (let [calls (atom [])
          worker (stub-worker calls (fn [_ _]))
          handlers (atom [])]
      (try
        (await (render/with-scope (test-instance calls worker)
                 {:cancel-signal (fn [_] nil)
                  :cancelled? (fn [_] false)
                  :on-cancel (fn [_ f] (swap! handlers conj f) nil)}
                 :job-1
                 (fn [render*]
                   (let [pending (render* (test-params :job-1) (fn [_] nil))]
                     ;; the cancel lands mid-render, once
                     ;; the worker is listening
                     (js/setTimeout (fn [] ((first @handlers))) 10)
                     pending))))
        (t/is false "with-scope should have rejected a terminated render")
        (catch :default cause
          (t/is (= :worker-exited (-> cause ex-data :code)))
          (t/is (some #{:terminate} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-takes-the-job-id-from-params
  (try
    (let [calls (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))]
      (await (render/render (test-instance calls worker)
                            (stub-cancel-source calls (atom false))
                            (test-params :job-9)
                            (fn [_] nil)))
      (t/is (some #{[:cancel-signal :job-9]} @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-without-job-id-wires-no-cancel
  (try
    (let [calls (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))]
      (await (render/render (test-instance calls worker)
                            (stub-cancel-source calls (atom false))
                            (dissoc (test-params :job-9) :job-id)
                            (fn [_] nil)))
      (t/is (not-any? #(and (vector? %) (not= :post (first %))) @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async failed-render-does-not-break-the-chain
  (try
    (let [calls (atom [])
          worker (stub-worker calls (render-behavior {:id "o1"}))
          seen (atom [])]
      (await (render/with-scope (test-instance calls worker)
               (stub-cancel-source calls (atom false))
               :job-1
               (^:async fn [render*]
                 (try
                   (await (render* (test-params :job-1)
                                   (fn [_] (throw (ex-info "on-object broke" {})))))
                   (catch :default _cause
                     (swap! calls conj :first-failed)))
                 (await (render* (test-params :job-1)
                                 (fn [object] (swap! seen conj object) nil))))))
      (t/is (some #{:first-failed} @calls))
      (t/is (= [{:id "o1"}] @seen)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
