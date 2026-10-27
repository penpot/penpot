;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.commands
  "Execution commands loaded after CLI validation."
  (:require
   [app.common.transit :as transit]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.run :as run]))

(enable-console-print!)

(defn- print-error
  [cause]
  (.error js/console (or (ex-message cause) (str cause)))
  (when-let [data (ex-data cause)]
    (.error js/console (pr-str data))))

(defn- ^:async run-command
  [root options]
  (let [controller     (js/AbortController.)
        signal         (.-signal controller)
        interrupted    (atom nil)
        remove-signals (process/on-signals
                        (fn [name]
                          (when-not (.-aborted signal)
                            (reset! interrupted name)
                            (.abort controller
                                    (ex-info "run interrupted"
                                             {:type ::interrupted :signal name})))))]
    (try
      (let [result (await (run/run root options signal
                                   #(.write (.-stderr js/process) %)
                                   #(println %)))]
        (when-let [error (:error result)]
          (print-error error))
        (:exit-code result))
      (catch :default cause
        (print-error cause)
        (case @interrupted
          "SIGINT" 130
          "SIGTERM" 143
          1))
      (finally
        (remove-signals)))))

(defn- ^:async build-command
  [root options]
  (let [controller     (js/AbortController.)
        remove-signals (process/on-signals #(.abort controller %))]
    (try
      (await (build/prepare {:root root
                             :features {:default-features? (:default-features options)
                                        :features (or (:features options) [])}
                             :build-timeout-ms (:build-timeout-ms options)
                             :signal (.-signal controller)
                             :on-created #(println (:id %))}))
      0
      (finally (remove-signals)))))

(defn ^:async execute
  "Accepts a validated Transit command from the CLI and returns its exit code.
  Each compiled module owns its CLJS values; only the string crosses modules.
  Execution errors are printed here before returning a numeric exit code."
  [request]
  (try
    (let [{:keys [command options]} (transit/decode-str request)
          root (artifacts/frontend-root)]
      (case command
        "purge"
        (do
          (artifacts/purge root (cond (:only-builds options) :builds
                                      (:only-runs options) :runs
                                      :else :all))
          0)

        "build"
        (await (build-command root options))

        "run"
        (await (run-command root options))))
    (catch :default cause
      (print-error cause)
      1)))
