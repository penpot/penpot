;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.runner
  "Command-line entry for renderer benchmarks and saved results."
  (:require
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.errors :refer [attempt failure?]]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.cli :as cli]
   [benches.render-wasm.node.compare :as compare]
   [benches.render-wasm.node.execution :as execution]
   [benches.render-wasm.node.merge :as merge]
   [benches.render-wasm.node.outcome :as outcome]))

(defn- help-command
  [_ {:keys [topic]}]
  (outcome/succeeded {:report (cli/get-help topic)}))

(defn- compare-command
  [_ {:keys [baseline-path candidate-path] :as options}]
  (let [result (compare/execute baseline-path candidate-path options)]
    (outcome/succeeded {:comparison (:comparison result) :report (:text result)})))

(defn- merge-command
  [{:keys [get-root]} {:keys [base-path additional-path] :as options}]
  (let [{:keys [run home]} (merge/execute (get-root) base-path additional-path options)]
    (outcome/succeeded {:run run :home home :log-path (:log-path home)
                        :report (str "Run " (:run-id run) "\nOutput " (:dir home))})))

(defn- purge-command
  [{:keys [get-root]} {:keys [target]}]
  (artifacts/purge (get-root) target)
  (outcome/succeeded {}))

(def ^:private handlers
  {"help" help-command "compare" compare-command "merge" merge-command "purge" purge-command})

(defn- ^:async run-cli
  [argv]
  (let [request (attempt :catch-fatal (cli/parse-resolve argv))]
    (if (failure? request)
      (execution/fail request)
      (if (contains? handlers (:command request))
        (await (execution/execute request handlers {:get-root artifacts/frontend-root}))
        (let [status (attempt :catch-fatal
                              (let [commands (js/require (path/join js/__dirname "commands.cjs"))]
                                (await (.execute commands (transit/encode-str request)))))]
          (if (failure? status)
            (execution/fail status (:console request) {})
            status))))))

(defn ^:async -main
  "Runs argv and sets the process exit status after publication and cleanup."
  [& argv]
  (set! (.-exitCode js/process) (await (run-cli argv))))
