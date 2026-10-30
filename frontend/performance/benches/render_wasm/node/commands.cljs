;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.commands
  "Execution commands loaded after CLI validation."
  (:require
   [app.common.schema :as sm]
   [app.common.transit :as transit]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.feature-comparison :as feature-comparison]
   [benches.render-wasm.node.feature-plan :as feature-plan]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.options :as options]
   [benches.render-wasm.node.outcome :as outcome]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.run :as run]))

(defn- ^:async with-interruption
  "Calls `(f signal)` with an AbortSignal that SIGINT and SIGTERM abort, and
  returns its outcome. A failure becomes an outcome with exit code 1. After an
  interruption the exit code is 130 or 143 and only fatal failures stay in
  the errors: the others come from the aborted work."
  [f]
  (let [controller     (js/AbortController.)
        signal         (.-signal controller)
        interrupted    (atom nil)
        remove-signals (process/on-signals
                        (fn [signal-name]
                          (when-not (.-aborted signal)
                            (reset! interrupted signal-name)
                            (.abort controller
                                    (ex-info "interrupted"
                                             {:type ::interrupted :signal signal-name})))))]
    (try
      (let [result   (attempt :catch-fatal (await (f signal)))
            finished (if (failure? result) (outcome/failed result {}) result)]
        (cond-> finished
          @interrupted (-> (update :errors #(filterv errors/fatal? %))
                           (assoc :exit-code (outcome/interruption-code @interrupted)))))
      (finally
        (remove-signals)))))

(defn- run-command
  [root options]
  (with-interruption
    #(run/run root (select-keys options (sm/keys run/schema:run-options)) %)))

(defn- build-command
  [root options]
  (with-interruption
    (^:async fn [signal]
      (await (build/prepare {:root root
                             :features {:default-features? (:default-features options)
                                        :features (or (:features options) [])}
                             :build-timeout-ms (:build-timeout-ms options)
                             :signal signal
                             :on-created (fn [build]
                                           (log/result (str "Build " (:id build)))
                                           (log/result (str "Output " (:dir build))))}))
      {:exit-code 0})))

(defn- ab-command
  [root options]
  (with-interruption
    (^:async fn [signal]
      (let [prepared (await (feature-plan/prepare root options signal))]
        (if (:dry-run options)
          (do
            (log/result (feature-plan/format-plan prepared))
            {:exit-code 0})
          (await (feature-comparison/execute root prepared signal {})))))))

(defn- ^:async run-request
  "Runs the validated command and returns its outcome."
  [request]
  (let [{:keys [command options]} (transit/decode-str request)
        _ (when (and (:require-gpu options)
                     (or (= command "run")
                         (and (= command "ab") (not (:dry-run options))))
                     (not= "1" (.-PENPOT_GPU (.-env js/process))))
            (throw (ex-info "--require-gpu needs PENPOT_GPU=1. Start the container with ./manage.sh run-devenv --gpu and use pnpm run render-bench."
                            {:type ::gpu-required})))
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
      (await (ab-command root options)))))

(defn- log-failures
  "Prints each failure of `result`. Only the first prints a log tail."
  [result]
  (binding [log/*log-path* (:log-path result)]
    (doseq [[index cause] (map-indexed vector (:errors result))]
      (let [data (ex-data cause)]
        (log/error {:message (errors/message-of cause)
                    :fields (filterv (comp some? second)
                                     [[:type (:type data)] [:phase (:phase data)]
                                      [:build (:build-id data)] [:reason (:reason data)]
                                      [:manifest-error (:manifest-error data)]])
                    :tail-log (:log-path data)
                    :tail? (zero? index)})))))

(defn ^:async execute
  "Accepts a validated Transit command from the CLI and returns its exit code.
  Each compiled module owns its CLJS values; only the string crosses modules.
  Execution errors are printed here before returning a numeric exit code."
  [request]
  (log/configure {})
  (let [result   (attempt :catch-fatal (await (run-request request)))
        finished (if (failure? result) (outcome/failed result {}) result)]
    (log-failures finished)
    (:exit-code finished)))
