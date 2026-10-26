;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.runner
  "Build preparation and managed artifact cleanup for renderer benchmarks."
  (:require
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.process :as process]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]))

(enable-console-print!)

(def cli-options
  [["-h" "--help" "Print this help"]
   [nil "--[no-]default-features" "Enable Cargo defaults" :default true]
   [nil "--features FEATURES" "Comma-separated functional Cargo features"
    :parse-fn build/parse-feature-list]
   [nil "--build-timeout-ms MS" "Complete preparation deadline"
    :default process/default-build-timeout-ms :parse-fn js/Number
    :validate [#(and (js/Number.isSafeInteger %) (pos? %)) "Must be a positive integer"]]
   [nil "--only-builds" "Purge build attempts"]
   [nil "--only-runs" "Purge managed runs"]])

(defn usage
  [summary]
  (str "Usage: runner build [options]\n"
       "       runner purge [--only-builds | --only-runs]\n\n"
       "build prints one attempt ID after storage initialization. Exit 0 means\n"
       "ready; errors retain the build.transit and build.log for that ID.\n"
       "Progress goes to stderr. Preparation requires an idle checkout with\n"
       "dependencies installed. Keep renderer watchers stopped until it ends.\n\n"
       "purge requires an idle machine and deletes managed builds and runs.\n\n"
       summary))

(defn parse-args
  [args]
  (let [args (cond-> args (= "--" (first args)) rest)
        {:keys [arguments options errors summary]} (parse-opts args cli-options)
        command (first arguments)
        flags   (into #{} (comp (filter #(str/starts-with? % "--"))
                                (map #(first (str/split % #"=" 2)))) args)]
    (when (seq errors)
      (throw (ex-info (str/join "\n" errors) {:type ::invalid-args})))
    (when-not (:help options)
      (when-not (and (= 1 (count arguments)) (contains? #{"build" "purge"} command))
        (throw (ex-info "expected build or purge; try --help" {:type ::invalid-args})))
      (when (and (:only-builds options) (:only-runs options))
        (throw (ex-info "--only-builds and --only-runs are mutually exclusive" {:type ::invalid-args})))
      (when (and (= command "build") (some flags ["--only-builds" "--only-runs"]))
        (throw (ex-info "purge category flags require purge" {:type ::invalid-args})))
      (when (and (= command "purge")
                 (some flags ["--features" "--default-features" "--no-default-features" "--build-timeout-ms"]))
        (throw (ex-info "build options require build" {:type ::invalid-args}))))
    {:command command :options options :summary summary}))

(defn ^:async -main
  [& args]
  (try
    (let [{:keys [command options summary]} (parse-args args)
          root (artifacts/frontend-root)]
      (cond
        (:help options) (println (usage summary))
        (= command "purge")
        (artifacts/purge root (cond (:only-builds options) :builds
                                    (:only-runs options) :runs
                                    :else :all))
        (= command "build")
        (let [controller (js/AbortController.)
              remove-signals (process/on-signals #(.abort controller %))]
          (try
            (await (build/prepare {:root root
                                   :features {:default-features? (:default-features options)
                                              :features (or (:features options) [])}
                                   :build-timeout-ms (:build-timeout-ms options)
                                   :signal (.-signal controller)
                                   :on-created #(println (:id %))}))
            (finally (remove-signals))))))
    (catch :default cause
      (.error js/console (or (ex-message cause) (str cause)))
      (when-let [data (ex-data cause)] (.error js/console (pr-str data)))
      (set! (.-exitCode js/process) 1))))
