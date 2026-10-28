;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.feature-plan
  "Plan comparisons based on cargo metadata and cases collected."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.feature-plan :as feature-plan]
   [benches.render-wasm.node.options :as options]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.runs :as runs]
   [clojure.string :as str]))

(defn- get-root-features
  [cwd text]
  (let [metadata (js->clj (js/JSON.parse text))
        manifest (fs/realpathSync (path/join cwd "Cargo.toml"))
        package  (first (filter #(= manifest (fs/realpathSync
                                              (path/resolve cwd (get % "manifest_path"))))
                                (get metadata "packages")))]
    (when-not package
      (throw (ex-info "Cargo metadata does not contain the renderer manifest"
                      {:type ::missing-package :manifest-path manifest})))
    (get package "features")))

(defn ^:async prepare
  "Accepts the frontend root and resolved CLI options. Reads Cargo metadata in
  render-wasm with the inherited environment, checks an optional fresh output
  path, and collects selected case data once."
  [root opts signal]
  (let [opts       (merge {:max-features feature-plan/default-max-features
                           :confirm false :dry-run false :seed 42
                           :allow-build-differences false
                           :allow-scene-differences false
                           :allow-all-differences false}
                          opts
                          {:default-features false})
        plan       (feature-plan/plan (:features opts) opts)
        output-dir (runs/check-output-dir (:output-dir opts))
        cwd        (path/resolve root ".." "render-wasm")
        metadata   (await (process/run
                           {:command "cargo"
                            :args ["metadata" "--offline" "--locked" "--no-deps" "--format-version" "1"]
                            :cwd cwd
                            :deadline (process/deadline (:build-timeout-ms opts options/default-build-timeout-ms))
                            :signal signal}))]
    (feature-plan/check-manifest (:features plan) (get-root-features cwd (:stdout metadata)))
    {:plan plan
     :cases (cases/collect-cases {:master-seed (:seed opts) :filter (:filter opts)})
     :output-dir output-dir
     :options (assoc opts :features (:features plan) :output-dir output-dir)}))

(defn format-plan
  "Formats prepared data without collecting again or reading the filesystem."
  [{:keys [plan cases options]}]
  (str "Feature comparison plan\n"
       "Features: " (str/join ", " (:features plan)) "\n"
       "Configurations (Cargo defaults disabled):\n"
       (str/join "\n" (map #(str "  " (:slug %) ": "
                                 (str/join ", " (get-in % [:selection :features])))
                           (:configurations plan))) "\n"
       "Edges:\n"
       (str/join "\n" (map #(str "  " (:from %) " -> " (:to %) ": " (:feature %))
                           (:edges plan))) "\n"
       "Cases: " (count cases) "\n"
       "Attempts per case: " (:warmups options) " warmups, " (:repetitions options) " measured\n"
       "Timeouts (ms): attempt " (:timeout-ms options)
       ", build " (:build-timeout-ms options) ", startup " (:startup-timeout-ms options) "\n"))
