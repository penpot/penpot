;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.cli.deferred
  "Deferred execution entry for build, run and feature comparison commands."
  (:require
   [app.common.transit :as transit]
   [benches.render-wasm.errors :refer [attempt failure?]]
   [benches.render-wasm.node.cli.execution :as execution]
   [benches.render-wasm.node.outcome :as outcome]
   [benches.render-wasm.node.runner.build :as build]
   [benches.render-wasm.node.runner.feature-comparison :as feature-comparison]
   [benches.render-wasm.node.runner.run :as run]
   [benches.render-wasm.node.storage.artifacts :as artifacts]))

(defn- ^:async build-command
  [{:keys [get-root signal]} options]
  (let [created (atom nil)
        result  (attempt :catch-fatal
                         (await (build/prepare
                                 {:root (get-root)
                                  :selection {:default-features? (:default-features options)
                                              :features (:features options)}
                                  :build-timeout-ms (:build-timeout-ms options)
                                  :signal signal :on-created #(reset! created %)})))
        result  (if (failure? result)
                  (outcome/failed result {:build @created})
                  (outcome/succeeded {:build result :log-path (:log-path result)}))]
    (cond-> result
      @created (assoc :report (str "Build " (:id @created) "\nOutput " (:dir @created))))))

(defn- ^:async run-command
  [{:keys [get-root signal services]} options]
  (let [{:keys [home report] :as result} (await (run/run (get-root) options signal services))]
    (assoc result :report (str "Run " (:run-id home) "\nOutput " (:dir home)
                               (when report (str "\nReport\n" report))))))

(defn- ^:async ab-command
  [{:keys [get-root signal services]} options]
  (let [root     (get-root)
        prepared (await (feature-comparison/prepare root options signal))]
    (if (:dry-run options)
      (outcome/succeeded {:report (feature-comparison/format-plan prepared)})
      (let [{:keys [home exit-code] :as result}
            (await (feature-comparison/execute root prepared signal {:services services}))]
        (cond-> result
          home (assoc :report (str "Comparison " (:comparison-id home)
                                   "\nOutput " (:dir home)
                                   "\nStatus " (cond
                                                 (.-aborted signal) "interrupted"
                                                 (zero? exit-code) "completed"
                                                 :else "failed"))))))))

(def ^:private handlers
  {"build" build-command "run" run-command "ab" ab-command})

(defn ^:async execute
  "Accepts a Transit string containing a complete request and returns its
  numeric exit status after final publication."
  [text]
  (let [request (attempt :catch-fatal (transit/decode-str text))]
    (if (failure? request)
      (execution/fail request)
      (await (execution/execute request handlers {:get-root artifacts/frontend-root})))))
