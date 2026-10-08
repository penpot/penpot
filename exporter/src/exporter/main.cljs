;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.main
  "Entry point of the new exporter tree, modeled on the backend's
  `app.main`: a `system-config` map wired with refs, and `start`,
  `stop` and `restart` managing the running system.

  This namespace will replace `app.core` once every piece of `app.*`
  has been ported over. Until then the shadow build still boots
  `app.core`; switching the entry point comes last."
  (:require
   ["node:process" :as proc]
   ["node:worker_threads" :as wt]
   [app.common.logging :as l]
   [app.config :as cf]
   ;; Loaded for their init-key/halt-key methods, which is what makes
   ;; the pools and the queue consumer part of the system below.
   [exporter.browser]
   [exporter.consumer.config :as ccfg]
   [exporter.consumer.worker]
   [exporter.utils.system :as system]
   [exporter.wasm.pool]))

(l/setup! {:exporter :info})

(def system-config
  "The production wiring. Env-derived values are read here, at the
  wiring layer; the components themselves take plain data. The worker
  renders through a view of the running pools, resolved by refs, plus
  the static render config."
  {:exporter.browser/pool    {:max (ccfg/concurrency)}
   :exporter.wasm.pool/pool  {:max (ccfg/concurrency)}
   :exporter.consumer/worker {:concurrency (ccfg/concurrency)
                              :queue-key   (ccfg/queue-key)
                              :render      {:exporter.browser/pool   (system/ref :exporter.browser/pool)
                                            :exporter.wasm.pool/pool (system/ref :exporter.wasm.pool/pool)
                                            :base-uri                (cf/get-internal-uri)
                                            :public-uri              (cf/get :public-uri)
                                            :svgo?                   (contains? cf/flags :exporter-svgo)}}})

;; The running system map, or nil when nothing is started.
;; Counterpart of the backend's `app.system/system` var.
(defonce system
  (atom nil))

;; The config the running system booted with. `restart` reboots it,
;; so tests can cycle a fake config without touching production.
(defonce ^:private config-used
  (atom nil))

(defn ^:async start-custom
  "Boot the given config, halting the running system first, like the
  backend's `start-custom`. Resolves to `:started`."
  [config]
  (when-let [sys @system]
    (await (system/halt sys)))
  (swap! config-used (constantly config))
  (reset! system nil)
  (let [sys (await (system/init config))]
    (reset! system sys)
    :started))

;; Install-once guard: `start` runs on every hot reload, and piling
;; process listeners would end in MaxListenersExceeded warnings.
(defonce ^:private handlers-installed? (atom false))

(defn install-process-handlers
  "Register the process-level handlers: log uncaught exceptions instead
  of dying silently, and exit on SIGTERM/SIGINT. Signals only reach the
  main thread; exiting in a render worker would take down that worker
  rather than the process. Runs on `start`, never on namespace load,
  and only the first call takes effect."
  []
  (when (compare-and-set! handlers-installed? false true)
    (.on proc/default "uncaughtException"
         (fn [cause]
           (js/console.error cause)))
    (when ^boolean wt/isMainThread
      (.on proc/default "SIGTERM" (fn [] (proc/exit 0)))
      (.on proc/default "SIGINT" (fn [] (proc/exit 0))))))

(defn ^:async start
  "Boot the production wiring. Resolves to `:started`."
  []
  (install-process-handlers)
  (l/info :msg "starting exporter"
          :workers (ccfg/concurrency)
          :version (:full cf/version))
  (await (start-custom system-config)))

(defn ^:async stop
  "Halt the running system, if any. Resolves to `:stopped`."
  []
  (when-let [sys @system]
    (await (system/halt sys)))
  (reset! system nil)
  :stopped)

(defn ^:async restart
  "Halt and reboot whatever config is running (or the production
  wiring when nothing ever started). Resolves to `:restarted`."
  []
  (await (stop))
  (await (start-custom (or @config-used system-config)))
  :restarted)

(defn ^:dev/before-load-async on-before-load
  "Hot reload hook: halt the running system before shadow swaps the
  code. `done` always runs, so a halt failure is logged, never a
  stuck reload."
  [done]
  (-> (stop)
      (.then (fn [_] (done)))
      (.catch (fn [cause]
                (l/warn :hint "hot reload stop failed"
                        :cause (ex-message cause))
                (done)))))

(defn ^:dev/after-load-async on-after-load
  "Hot reload hook: boot the production wiring after shadow swaps the
  code. `done` always runs, so a boot failure is logged, never a
  stuck reload."
  [done]
  (-> (start)
      (.then (fn [_] (done)))
      (.catch (fn [cause]
                (l/warn :hint "hot reload start failed"
                        :cause (ex-message cause))
                (done)))))

(def main start)
