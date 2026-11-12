;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.cli.commands.run
  "Options for a benchmark run and its selected build source."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.cli.commands.build :as build]
   [benches.render-wasm.node.cli.shared :as shared]))

(def schema:build
  [:multi {:dispatch :kind}
   [:prepare
    [:merge build/schema:options
     [:map {:closed true}
      [:kind [:= :prepare]]]]]
   [:existing
    [:map {:closed true}
     [:kind [:= :existing]]
     [:id shared/schema:build-id]]]])

(def schema:options
  [:merge shared/schema:measurement
   [:map {:closed true}
    [:build schema:build]
    [:remove {:default false} :boolean]]])

(def ^:const usage "runner run [options]")

(def ^:const description
  "Run cases using existing or new builds.
Managed output: frontend/target/performance/render-wasm/runs/<run-id>/run.transit")

(def cli-options
  (into [[nil "--build ID" "Reuse a ready build" :id :build-id
          :validate [(sm/validator shared/schema:build-id) "must be a benchmark artifact ID"]]]
        (concat build/cli-options shared/measurement-options
                [[nil "--remove" "Remove the used build after shutdown"]])))

(defn resolve-options
  "Selects an existing build or a preparation branch from supplied flags."
  [options arguments]
  (shared/check-arguments arguments 0)
  (let [preparation (select-keys options [:default-features :features :build-timeout-ms])]
    (when (and (contains? options :build-id) (seq preparation))
      (throw (ex-info "--build cannot be combined with --features, --[no-]default-features or --build-timeout-ms"
                      {:type ::shared/invalid-args})))
    (assoc (dissoc options :build-id :default-features :features :build-timeout-ms)
           :build (if (contains? options :build-id)
                    {:kind :existing :id (:build-id options)}
                    (assoc preparation :kind :prepare)))))

(defn check-options
  "Checks measurement feature policy for the selected build source."
  [options]
  (when (= :prepare (get-in options [:build :kind]))
    (build/check-options (:build options)))
  options)

(defn get-help-defaults
  "Returns CLI defaults for run options with a preparation source."
  [options]
  (assoc (dissoc options :build)
         :default-features (get-in options [:build :default-features])
         :features (get-in options [:build :features])
         :build-timeout-ms (get-in options [:build :build-timeout-ms])))
