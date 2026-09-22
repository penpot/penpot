;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runner
  "Node CLI entry for the CLJS renderer benchmark suite.

  Scaffolding only. Commands, options, result reporting and failure
  accounting are owned by the tickets referenced below."
  (:require
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]))

(enable-console-print!)

(def cli-options
  [["-h" "--help" "Print this help"]])

(defn- argv
  []
  (let [args (->> (.-argv js/process)
                  (array-seq)
                  (drop 2))]
    ;; `pnpm run <script> -- ...` forwards the separator, so drop one
    ;; leading `--` before handing the args to tools.cli.
    (cond-> args
      (= "--" (first args)) rest)))

(defn- usage
  [summary]
  (str "Usage: node target/renderer-benchmarks/runner.cjs [options]\n\n"
       "Options:\n"
       summary "\n\n"
       "No command is implemented yet. Upcoming tickets add:\n"
       "  run      benchmark standard scenes and operations\n"
       "  ab       feature A/B matrix\n"
       "  compare  offline comparison of two result files\n\n"
       "Commands, options and reporting arrive with the later renderer\n"
       "benchmark tickets."))

(defn- fail!
  [message]
  (js/console.error message)
  ;; Set the exit code instead of calling `process.exit` so piped output is
  ;; not truncated; nothing else keeps the process alive.
  (set! (.-exitCode js/process) 1))

(defn -main
  [& _]
  (let [{:keys [options arguments errors summary]} (parse-opts (argv) cli-options)]
    (cond
      (:help options)
      (println (usage summary))

      (seq errors)
      (fail! (str/join "\n" errors))

      ;; TODO(mem:render-wasm/performance/cljs-rewrite/10-process-build-and-server,
      ;;      mem:render-wasm/performance/cljs-rewrite/11-runner-and-failure-accounting,
      ;;      mem:render-wasm/performance/cljs-rewrite/14-custom-measured-operations):
      ;; dispatch `run`, `ab` and `compare` instead of failing.
      (seq arguments)
      (fail! (str "Unknown command: " (str/join " " arguments) "\nTry --help"))

      :else
      (fail! "No command given\nTry --help"))))
