;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.main-test
  "The entry point of the new tree: the namespace loads, and
  start/stop/restart manage the running system."
  (:require
   ["node:os" :as os]
   ["node:path" :as path]
   ["node:process" :as proc]
   [app.common.logging :as l]
   [app.config :as cf]
   [cljs.test :as t :include-macros true]
   [exporter.browser :as browser]
   [exporter.consumer.config :as ccfg]
   [exporter.main :as main]
   [exporter.utils.system :as system]))

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
                                             (js/Promise.resolve (stub-browser calls)))}
   :exporter.wasm/pool    {:max           1
                           :min           0
                           :create-worker (fn []
                                            (swap! calls conj :create-worker)
                                            (js/Promise.resolve #js {}))}
   :exporter/renderer     {:exporter.browser/pool (system/ref :exporter.browser/pool)
                           :exporter.wasm/pool    (system/ref :exporter.wasm/pool)
                           :exporter/tmpdir      (system/ref :exporter/tmpdir)
                           :base-uri              "http://internal/"
                           :public-uri            "http://public/"
                           :svgo?                 false}
   :exporter/tmpdir      {:path (path/join (os/tmpdir) "penpot-exporter-test")}})

(defn- pool-of
  []
  (:exporter.browser/pool @main/system))

(defn- wasm-pool-of
  []
  (:exporter.wasm/pool @main/system))

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

(t/deftest production-wiring-declares-both-pools
  (t/testing "the wiring names the browser and the wasm pools, sized to the worker concurrency"
    (t/is (= {:max (ccfg/concurrency)}
             (:exporter.browser/pool main/system-config)))
    (t/is (= {:max (ccfg/concurrency)}
             (:exporter.wasm/pool main/system-config))))
  (t/testing "the renderer owns the render config, pools by ref"
    (let [renderer (:exporter/renderer main/system-config)]
      (t/is (= {:exporter.browser/pool (system/ref :exporter.browser/pool)
                :exporter.wasm/pool    (system/ref :exporter.wasm/pool)}
               (select-keys renderer
                            [:exporter.browser/pool :exporter.wasm/pool])))))
  (t/testing "the queue consumer names the renderer it renders through"
    (let [worker (:exporter.consumer/worker main/system-config)]
      (t/is (= (ccfg/concurrency) (:concurrency worker)))
      (t/is (= (ccfg/queue-key) (:queue-key worker)))
      (t/is (= (system/ref :exporter/renderer) (:renderer worker)))
      (t/is (= (system/ref :exporter/tmpdir) (:exporter/tmpdir worker)))))
  (t/testing "the temp area is wired from the environment"
    (t/is (= {:path (cf/get :tempdir)}
             (:exporter/tmpdir main/system-config)))))

;; NOTE: no test boots the production wiring: the wasm pool warms one
;; worker eagerly (min 1), and in this process the worker script would
;; be the test bundle itself. The hook path stays covered through the
;; fake configs below; the production map above is data, asserted
;; without booting it.

(t/deftest ^:async start-boots-and-stop-halts
  (try
    (let [calls (atom [])]
      (t/is (= :started (await (main/start-custom (fake-config calls)))))
      (t/is (some? (pool-of)))
      (t/testing "the renderer boots as a render fn over the resolved pools"
        (t/is (fn? (:exporter/renderer @main/system))))
      (t/testing "the temp area boots before anything that writes to it"
        (t/is (= (path/join (os/tmpdir) "penpot-exporter-test")
                 (:exporter/tmpdir @main/system))))
      (t/testing "the wasm pool rides the same lifecycle"
        (t/is (some? (:pool (wasm-pool-of))))
        (t/is (= 300000 (:timeout-ms (wasm-pool-of)))))
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
