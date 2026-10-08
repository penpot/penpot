;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.renderer-test
  "The renderer facade: malli validation and dispatch to the browser
  or headless drivers, against a stub browser pool and a stub wasm
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

(defn- stub-cancel-source
  [calls]
  {:cancel-signal (fn [job-id] (swap! calls conj [:cancel-signal job-id]) nil)
   :cancelled?    (fn [job-id] (swap! calls conj [:cancelled?-asked job-id]) false)
   :on-cancel     (fn [job-id f] (swap! calls conj [:on-cancel job-id f]) nil)})

(defn- test-env
  [calls]
  (let [worker (stub-worker calls)]
    {:browser-pool  (stub-browser-pool calls)
     :base-uri      (cf/get-internal-uri)
     :public-uri    (cf/get :public-uri)
     :svgo?         false
     :wasm-pool     {:pool (stub-wasm-pool calls worker) :timeout-ms 5000}
     :cancel-source (stub-cancel-source calls)}))

(defn- test-params
  [type opts]
  (merge {:file-id (uuid/next)
          :page-id (uuid/next)
          :token   "token"
          :scale   1
          :type    type
          :objects [{:id "a" :name "a" :suffix ".png" :filename "a.png"}]}
         opts))

(t/deftest headless-reads-the-wasm-flag
  (t/is (true? (renderer/headless? {:is-wasm true :type :png})))
  (t/is (false? (renderer/headless? {:type :png})))
  (t/is (false? (renderer/headless? {:is-wasm false :type :png}))))

(t/deftest ^:async render-routes-bitmaps-to-the-browser
  (try
    (let [calls (atom [])
          seen  (atom [])]
      (await (renderer/render (test-env calls)
                              (test-params :png nil)
                              (fn [object] (swap! seen conj object) nil)))
      (t/is (= ["a"] (mapv :id @seen)))
      (t/is (some #(and (vector? %) (= :goto (first %))) @calls))
      (t/is (not-any? #(and (vector? %) (= :post (first %))) @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-routes-headless-to-the-worker
  (try
    (let [calls  (atom [])
          job-id (uuid/next)
          seen   (atom [])]
      (await (renderer/render (test-env calls)
                              (test-params :png {:is-wasm true :job-id job-id})
                              (fn [object] (swap! seen conj object) nil)))
      (t/is (= [{:id "w1" :path "/tmp/w1.png"}] @seen))
      (t/is (some #{[:post "render"]} @calls))
      (t/is (some #{[:cancel-signal job-id]} @calls))
      (t/is (not-any? #(and (vector? %) (= :goto (first %))) @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-rejects-invalid-params
  (try
    (let [calls (atom [])]
      (try
        (await (renderer/render (test-env calls)
                                (dissoc (test-params :png nil) :objects)
                                (fn [_] nil)))
        (t/is false "render should have rejected params without objects")
        (catch :default cause
          (t/is (= :data-validation (-> cause ex-data :code))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async render-rejects-a-non-fn-callback
  (try
    (let [calls (atom [])]
      (try
        (await (renderer/render (test-env calls) (test-params :png nil) :not-a-fn))
        (t/is false "render should have rejected a non-fn on-object")
        (catch :default cause
          (t/is (= :data-validation (-> cause ex-data :code))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async with-scope-shares-one-worker-across-headless-renders
  (try
    (let [calls  (atom [])
          job-id (uuid/next)
          env    (test-env calls)
          seen   (atom [])]
      (await (renderer/with-scope env
               [(test-params :png {:is-wasm true :job-id job-id})
                (test-params :png {:is-wasm true :job-id job-id})
                (test-params :png {:job-id job-id})]
               (^:async fn [render*]
                 (await (render* (test-params :png {:is-wasm true :job-id job-id})
                                 (fn [object] (swap! seen conj object) nil)))
                 (await (render* (test-params :png {:is-wasm true :job-id job-id})
                                 (fn [object] (swap! seen conj object) nil)))
                 (await (render* (test-params :png {:job-id job-id})
                                 (fn [object] (swap! seen conj object) nil))))))
      (t/is (= 1 (count (filter #{:acquire} @calls))))
      (t/is (= 1 (count (filter #{:release} @calls))))
      (t/is (= 3 (count @seen))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async with-scope-without-headless-renders-directly
  (try
    (let [calls (atom [])
          env   (test-env calls)
          seen  (atom [])]
      (await (renderer/with-scope env
               [(test-params :png nil)]
               (fn [render*]
                 (render* (test-params :png nil)
                          (fn [object] (swap! seen conj object) nil)))))
      (t/is (= ["a"] (mapv :id @seen)))
      (t/is (not-any? #{:acquire} @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
