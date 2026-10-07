;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.browser-test
  "The browser pool service: lifecycle through the system, and the
  acquire/use/release contract of exec, against a fake browser factory."
  (:require
   ["generic-pool/lib/errors.js" :as gpe]
   [cljs.test :as t :include-macros true]
   [exporter.browser :as browser]
   [exporter.utils.system :as system]))

(defn- stub-page
  []
  #js {:marker :stub-page})

(defn- stub-context
  [calls page]
  #js {:newPage (fn [] (swap! calls conj :new-page) (js/Promise.resolve page))
       :close   (fn [] (swap! calls conj :close-context) (js/Promise.resolve nil))})

(defn- stub-browser
  [calls]
  (let [page (stub-page)]
    #js {:newContext  (fn [_opts]
                        (swap! calls conj :new-context)
                        (js/Promise.resolve (stub-context calls page)))
         :isConnected (fn [] true)
         :close       (fn []
                        (swap! calls conj :close-browser)
                        (js/Promise.resolve nil))}))

(defn- fake-create-browser
  [calls]
  (fn []
    (swap! calls conj :create-browser)
    (js/Promise.resolve (stub-browser calls))))

(defn- test-config
  [calls]
  {:exporter.browser/pool {:exporter.browser/max            1
                           :exporter.browser/create-browser (fake-create-browser calls)}})

(t/deftest ^:async pool-starts-and-stops-through-the-system
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls)))]
      (t/is (some? (:exporter.browser/pool sys)))
      (t/is (nil? (await (system/halt sys)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async exec-acquires-runs-handle-and-releases
  (try
    (let [calls  (atom [])
          sys    (await (system/init (test-config calls)))
          pool   (:exporter.browser/pool sys)
          seen   (atom nil)
          result (await (browser/exec pool {} (fn [page] (reset! seen page) :handle-result)))]
      (t/is (= :handle-result result))
      (t/is (= :stub-page (.-marker @seen)))
      (t/is (= [:create-browser :new-context :new-page :close-context] @calls))
      (t/is (not (some #{:close-browser} @calls)))
      (t/testing "a second exec reuses the released browser: nothing is created again"
        (await (browser/exec pool {} (fn [_] :again)))
        (t/is (= 1 (count (filter #{:create-browser} @calls)))))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async exec-destroys-the-browser-on-failure
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls)))
          pool  (:exporter.browser/pool sys)]
      (try
        (await (browser/exec pool {} (fn [_] (js/Promise.reject (ex-info "render broke" {})))))
        (t/is false "exec should have rejected when the handle fails")
        (catch :default cause
          (t/is (= "render broke" (ex-message cause)))
          (t/is (some #{:close-browser} @calls))))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async exec-without-pool-fails-fast
  (try
    (await (browser/exec nil {} (fn [_] :never)))
    (t/is false "exec should have rejected without a pool")
    (catch :default cause
      (t/is (= ::browser/not-started (-> cause ex-data :reason))))))

(t/deftest ^:async exec-translates-playwright-timeouts
  (try
    (let [calls (atom [])
          sys   (await (system/init (test-config calls)))
          pool  (:exporter.browser/pool sys)]
      (try
        (await (browser/exec pool {} (fn [_] (js/Promise.reject (gpe/TimeoutError. "timeout exceeded")))))
        (t/is false "exec should have rejected on a playwright timeout")
        (catch :default cause
          (t/is (= :timeout (-> cause ex-data :code)))))
      (await (system/halt sys)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
