;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.ab
  "Options for comparing configurations of Cargo features."
  (:require
   [benches.render-wasm.feature-plan :as feature-plan]
   [benches.render-wasm.node.cli.shared :as shared]
   [benches.render-wasm.node.options :as options]))

(def schema:max-features
  [:int {:min 1 :max 6}])

(def schema:options
  [:merge shared/schema:measurement shared/schema:comparison-permissions
   [:map {:closed true}
    [:features [:vector {:min 1} shared/schema:feature]]
    [:max-features {:default feature-plan/default-max-features} schema:max-features]
    [:confirm {:default false} :boolean]
    [:dry-run {:default false} :boolean]
    [:build-timeout-ms {:default options/default-build-timeout-ms} shared/schema:timeout-ms]]])

(def ^:const usage "runner ab --features A,B [options]")

(def ^:const description
  "Compare configurations of Cargo features.
  Managed output: frontend/target/performance/render-wasm/feature-comparisons/<ab-id>/feature-comparison.transit")

(def cli-options
  (into [[nil "--features FEATURES" "Comma-separated functional Cargo features"
          :parse-fn options/parse-feature-list]
         (shared/number-option "--max-features N" "Maximum number of requested features"
                               schema:max-features "must be an integer from 1 to 6")
         [nil "--confirm" "Confirm above eight configurations or with a blank filter"]
         [nil "--dry-run" "Validate and print the plan"]
         (shared/build-timeout-option "Deadline for metadata and each configuration build")]
        (concat shared/measurement-options shared/comparison-options)))

(defn resolve-options
  "Resolves AB arguments and requires a feature selection."
  [options arguments]
  (shared/check-arguments arguments 0)
  (when-not (contains? options :features)
    (throw (ex-info "ab requires at least one requested feature (--features)"
                    {:type ::shared/invalid-args})))
  options)

(defn check-options
  "Checks feature limits and confirmation for resolved AB options."
  [options]
  (feature-plan/plan (:features options) options)
  options)
