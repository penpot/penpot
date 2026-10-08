;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.renderer-test
  "The renderer facade: malli validation and dispatch to the browser
  or wasm drivers, against a stub browser pool and a stub wasm
  pool."
  (:require
   ["generic-pool" :as gp]
   ["node:events" :as events]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [cljs.test :as t :include-macros true]
   [exporter.renderer :as renderer]))

;; --- stub browser side (bitmap only: navigate once, shoot objects) ---

(defn- stub-locator
  [calls]
  #js {:waitFor    (fn [_opts] (js/Promise.resolve nil))
       :screenshot (fn [opts]
                     (swap! calls conj [:screenshot (unchecked-get opts "path")])
                     (js/Promise.resolve nil))})

(defn- stub-page
  [calls]
  #js {:goto            (fn [url _opts]
                          (swap! calls conj [:goto (str url)])
                          (js/Promise.resolve nil))
       :waitForTimeout  (fn [ms] (swap! calls conj [:sleep ms]) (js/Promise.resolve nil))
       :waitForFunction (fn [_src _arg _opts] (js/Promise.resolve nil))
       :locator         (fn [sel] (swap! calls conj [:select sel]) (stub-locator calls))
       :$$              (fn [sel]
                          (swap! calls conj [:select-all sel])
                          (js/Promise.resolve #js []))
       :evaluate        (fn [_f & _args] (js/Promise.resolve nil))})

(defn- stub-context
  [page]
  #js {:newPage (fn [] (js/Promise.resolve page))
       :close   (fn [] (js/Promise.resolve nil))})

(defn- stub-browser
  [page]
  #js {:newContext  (fn [_opts] (js/Promise.resolve (stub-context page)))
       :isConnected (fn [] true)
       :close       (fn [] (js/Promise.resolve nil))})

(defn- stub-browser-pool
  [calls]
  (let [page (stub-page calls)]
    (gp/createPool #js {:create   (fn [] (js/Promise.resolve (stub-browser page)))
                        :destroy  (fn [browser] (.close ^js browser))
                        :validate (fn [_] true)}
                   #js {:max 1 :min 0})))

;; --- stub wasm side (one worker answering object+done) ---

(defn- stub-worker
  [calls]
  (let [^js emitter (events/EventEmitter.)]
    (unchecked-set emitter "postMessage"
                   (fn [msg]
                     (swap! calls conj [:post (unchecked-get msg "type")])
                     (js/setTimeout
                      (fn []
                        (.emit emitter "message" #js {:type "object"
                                                      :payload (transit/encode-str {:id "w1" :path "/tmp/w1.png"})})
                        (.emit emitter "message" #js {:type "done"}))
                      0)))
    (unchecked-set emitter "terminate"
                   (fn []
                     (swap! calls conj :terminate)
                     (js/Promise.resolve nil)))
    emitter))

(defn- stub-wasm-pool
  [calls worker]
  #js {:acquire (fn [] (swap! calls conj :acquire) (js/Promise.resolve worker))
       :release (fn [_] (swap! calls conj :release) (js/Promise.resolve nil))
       :destroy (fn [_] (swap! calls conj :destroy) (js/Promise.resolve nil))})

(defn- stub-check
  [calls]
  (fn []
    (swap! calls conj :check-cancelled)))

(defn- test-cfg
  [calls]
  (let [worker (stub-worker calls)]
    {:exporter.browser/pool    (stub-browser-pool calls)
     :base-uri                 (cf/get-internal-uri)
     :public-uri               (cf/get :public-uri)
     :svgo?                    false
     :exporter.wasm.pool/pool {:pool (stub-wasm-pool calls worker) :timeout-ms 5000}}))

(defn- never-cancelled
  [calls]
  (stub-check calls))

(defn- test-params
  [type opts]
  (merge {:file-id (uuid/next)
          :page-id (uuid/next)
          :token   "token"
          :scale   1
          :type    type
          :objects [{:id "a" :name "a" :suffix ".png" :filename "a.png"}]}
         opts))

(t/deftest wasm-reads-the-wasm-flag
  (t/is (true? (renderer/wasm? {:is-wasm true :type :png})))
  (t/is (false? (renderer/wasm? {:type :png})))
  (t/is (false? (renderer/wasm? {:is-wasm false :type :png}))))

(t/deftest ^:async render-routes-bitmaps-to-the-browser
  (try
    (let [calls (atom [])
          seen  (atom [])]
      (await (renderer/render (test-cfg calls)
                              :exports [(test-params :png nil)]
                              :on-object (fn [object] (swap! seen conj object) nil)
                              :check-cancelled (never-cancelled calls)))
      (t/is (= ["a"] (mapv :id @seen)))
      (t/is (some #(and (vector? %) (= :goto (first %))) @calls))
      (t/is (not-any? #(and (vector? %) (= :post (first %))) @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-routes-wasm-to-the-worker
  (try
    (let [calls  (atom [])
          job-id (uuid/next)
          seen   (atom [])]
      (await (renderer/render (test-cfg calls)
                              :exports [(test-params :png {:is-wasm true :job-id job-id})]
                              :on-object (fn [object] (swap! seen conj object) nil)
                              :check-cancelled (never-cancelled calls)))
      (t/is (= [{:id "w1" :path "/tmp/w1.png"}] @seen))
      (t/is (some #{[:post "render"]} @calls))
      (t/is (some #{:check-cancelled} @calls))
      (t/is (not-any? #(and (vector? %) (= :goto (first %))) @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-rejects-invalid-params
  (try
    (let [calls (atom [])]
      (try
        (await (renderer/render (test-cfg calls)
                                :exports [(dissoc (test-params :png nil) :objects)]
                                :on-object (fn [_] nil)
                                :check-cancelled (never-cancelled calls)))
        (t/is false "render should have rejected params without objects")
        (catch :default cause
          (t/is (= :data-validation (-> cause ex-data :code))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-rejects-a-non-fn-callback
  (try
    (let [calls (atom [])]
      (try
        (await (renderer/render (test-cfg calls)
                                :exports [(test-params :png nil)]
                                :on-object :not-a-fn
                                :check-cancelled (never-cancelled calls)))
        (t/is false "render should have rejected a non-fn on-object")
        (catch :default cause
          (t/is (= :data-validation (-> cause ex-data :code))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-shares-one-worker-across-wasm-renders
  (try
    (let [calls  (atom [])
          job-id (uuid/next)
          cfg    (test-cfg calls)
          seen   (atom [])]
      (t/is (nil? (await (renderer/render cfg
                                          :exports [(test-params :png {:is-wasm true :job-id job-id})
                                                    (test-params :png {:is-wasm true :job-id job-id})
                                                    (test-params :png {:job-id job-id})]
                                          :on-object (fn [object] (swap! seen conj object) nil)
                                          :check-cancelled (never-cancelled calls)))))
      (t/is (= 1 (count (filter #{:acquire} @calls))))
      (t/is (= 1 (count (filter #{:release} @calls))))
      (t/is (= 3 (count @seen))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-without-wasm-renders-directly
  (try
    (let [calls (atom [])
          cfg   (test-cfg calls)
          seen  (atom [])]
      (await (renderer/render cfg
                              :exports [(test-params :png nil)]
                              :on-object (fn [object] (swap! seen conj object) nil)
                              :check-cancelled (never-cancelled calls)))
      (t/is (= ["a"] (mapv :id @seen)))
      (t/is (not-any? #{:acquire} @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-validates-before-leasing
  (try
    (let [calls  (atom [])
          job-id (uuid/next)
          cfg    (test-cfg calls)]
      (try
        (await (renderer/render cfg
                                :exports [(test-params :png {:is-wasm true :job-id job-id})
                                          (dissoc (test-params :png nil) :objects)]
                                :on-object (fn [_] nil)
                                :check-cancelled (never-cancelled calls)))
        (t/is false "render should have rejected the bad batch")
        (catch :default cause
          (t/is (= :data-validation (-> cause ex-data :code)))
          (t/testing "no worker was leased for a batch that never renders"
            (t/is (not-any? #{:acquire} @calls))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-rejects-unknown-task-keys
  (try
    (let [calls (atom [])]
      (try
        (await (renderer/render (test-cfg calls)
                                :exports [(test-params :png nil)]
                                :on-objec (fn [_] nil)
                                :check-cancelled (never-cancelled calls)))
        (t/is false "render should have rejected the misspelled key")
        (catch :default cause
          (t/is (= :data-validation (-> cause ex-data :code)))
          (t/is (not-any? #(and (vector? %) (= :goto (first %))) @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
