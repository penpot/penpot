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
   [benches.render-wasm.node.feature-comparison :as feature-comparison]
   [benches.render-wasm.node.feature-plan :as feature-plan]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.options :as options]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.run :as run]))

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
      (await (run/run root options signal))
      (catch :default cause
        {:error cause :log-path (:log-path (ex-data cause))
         :exit-code (case @interrupted "SIGINT" 130 "SIGTERM" 143 1)})
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
                             :on-created (fn [build]
                                           (log/result (str "Build " (:id build)))
                                           (log/result (str "Output " (:dir build))))}))
      {:exit-code 0}
      (finally (remove-signals)))))

(defn- ^:async ab-command
  [root options]
  (let [controller     (js/AbortController.)
        signal         (.-signal controller)
        interrupted    (atom nil)
        remove-signals (process/on-signals
                        (fn [name]
                          (when-not (.-aborted signal)
                            (reset! interrupted name)
                            (.abort controller name))))]
    (try
      (let [prepared (await (feature-plan/prepare root options signal))]
        (if (:dry-run options)
          (do
            (log/result (feature-plan/format-plan prepared))
            {:exit-code 0})
          (let [result (await (feature-comparison/execute root prepared signal {}))]
            (cond-> result
              @interrupted (assoc :exit-code (case @interrupted "SIGTERM" 143 130))))))
      (catch :default cause
        {:error cause :log-path (:log-path (ex-data cause))
         :exit-code (case @interrupted "SIGINT" 130 "SIGTERM" 143 1)})
      (finally
        (remove-signals)))))

(defn ^:async execute
  "Accepts a validated Transit command from the CLI and returns its exit code.
  Each compiled module owns its CLJS values; only the string crosses modules.
  Execution errors are printed here before returning a numeric exit code."
  [request]
  (log/configure {})
  (let [outcome
        (try
          (let [{:keys [command options]} (transit/decode-str request)
                root (artifacts/frontend-root)]
            (log/configure {:level (options/console-level options)
                            :color (options/color-mode options)})
            (case command
              "purge"
              (do
                (artifacts/purge root (cond (:only-builds options) :builds
                                            (:only-runs options) :runs
                                            (:only-comparisons options) :comparisons
                                            :else :all))
                {:exit-code 0})

              "build"
              (await (build-command root options))

              "run"
              (await (run-command root options))

              "ab"
              (await (ab-command root options))))
          (catch :default cause
            {:error cause :log-path (:log-path (ex-data cause)) :exit-code 1}))]
    (when-let [cause (or (:error outcome) (:storage-error outcome))]
      (let [data (ex-data cause)]
        (binding [log/*log-path* (:log-path outcome)]
          (log/error {:message (or (ex-message cause) (str cause))
                      :fields (filterv (comp some? second)
                                       [[:type (:type data)] [:phase (:phase data)]
                                        [:build (:build-id data)] [:reason (:reason data)]
                                        [:manifest-error (:manifest-error data)]])
                      :tail-log (:tail-log outcome)}))))
    (:exit-code outcome)))
