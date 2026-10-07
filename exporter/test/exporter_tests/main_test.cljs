;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.main-test
  "The entry point of the new tree: the namespace loads, and
  start/stop/restart manage the running system."
  (:require
   ["node:process" :as proc]
   [app.common.logging :as l]
   [cljs.test :as t :include-macros true]
   [exporter.browser :as browser]
   [exporter.main :as main]))

(t/deftest new-tree-loads
  (t/testing "exporter.main resolves once required"
    (t/is (some? (find-ns 'exporter.main)))))

(t/deftest entry-logger-allows-info
  (t/testing "the entry point leaves info logging enabled for the new tree"
    (t/is (true? (l/enabled? "exporter.main" :info)))))

(defn- stub-context
  [calls]
  #js {:newPage (fn [] (swap! calls conj :new-page) (js/Promise.resolve #js {}))
       :close   (fn [] (swap! calls conj :close-context) (js/Promise.resolve nil))})

(defn- stub-browser
  [calls]
  #js {:newContext  (fn [_opts]
                      (swap! calls conj :new-context)
                      (js/Promise.resolve (stub-context calls)))
       :isConnected (fn [] true)
       :close       (fn []
                      (swap! calls conj :close-browser)
                      (js/Promise.resolve nil))})

(defn- fake-config
  [calls]
  {:exporter.browser/pool {:max            1
                           :create-browser (fn []
                                             (swap! calls conj :create-browser)
                                             (js/Promise.resolve (stub-browser calls)))}})

(defn- pool-of
  []
  (:exporter.browser/pool @main/system))

(t/deftest ^:async install-process-handlers-registers-handlers
  (try
    (let [listeners (fn [event] (.listenerCount proc/default event))
          current   (fn [] (mapv listeners ["uncaughtException" "SIGTERM" "SIGINT"]))
          before    (current)]
      (main/install-process-handlers)
      (t/is (= (mapv inc before) (current)))
      (t/testing "a second install adds nothing: reloads must not pile listeners"
        (main/install-process-handlers)
        (t/is (= (mapv inc before) (current)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async hot-reload-stop-halts-through-done
  (try
    (let [calls (atom [])]
      (await (main/start-custom (fake-config calls)))
      (await (browser/exec (pool-of) {} (fn [_] :ok)))
      (let [stopped (js/Promise. (fn [resolve _] (main/on-before-load (fn [] (resolve :done)))))]
        (t/is (= :done (await stopped))))
      (t/is (nil? @main/system))
      (t/is (some #{:close-browser} @calls))
      (t/is (= :stopped (await (main/stop)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async hot-reload-start-boots-production-wiring
  (try
    ;; No browser launches: the pool starts empty (min 0) and nothing
    ;; checks one out, so this boots the real config with no Chromium.
    (let [started (js/Promise. (fn [resolve _] (main/on-after-load (fn [] (resolve :done)))))]
      (t/is (= :done (await started))))
    (t/is (some? (pool-of)))
    (t/is (= :stopped (await (main/stop))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async start-boots-and-stop-halts
  (try
    (let [calls (atom [])]
      (t/is (= :started (await (main/start-custom (fake-config calls)))))
      (t/is (some? (pool-of)))
      (await (browser/exec (pool-of) {} (fn [_] :ok)))
      (t/is (= :stopped (await (main/stop))))
      (t/is (nil? @main/system))
      (t/is (some #{:close-browser} @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async restart-reboots-the-same-config
  (try
    (let [calls (atom [])]
      (await (main/start-custom (fake-config calls)))
      (await (browser/exec (pool-of) {} (fn [_] :ok)))
      (t/is (= :restarted (await (main/restart))))
      (t/is (some #{:close-browser} @calls))
      (t/is (= :ok (await (browser/exec (pool-of) {} (fn [_] :ok)))))
      (t/is (= 2 (count (filter #{:create-browser} @calls))))
      (await (main/stop)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async second-start-replaces-the-running-system
  (try
    (let [calls (atom [])]
      (await (main/start-custom (fake-config calls)))
      (await (browser/exec (pool-of) {} (fn [_] :ok)))
      (t/is (= :started (await (main/start-custom (fake-config calls)))))
      (t/is (some #{:close-browser} @calls))
      (t/is (= :ok (await (browser/exec (pool-of) {} (fn [_] :ok)))))
      (await (main/stop)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
