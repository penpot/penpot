;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.cli.commands.build
  "Options for preparing a benchmark build."
  (:require
   [benches.render-wasm.features.selection :as selection]
   [benches.render-wasm.node.cli.shared :as shared]
   [benches.render-wasm.node.settings :as settings]))

(def schema:options
  [:map {:closed true}
   [:default-features {:default true} :boolean]
   [:features {:default []} [:vector shared/schema:feature]]
   [:build-timeout-ms {:default settings/default-build-timeout-ms} shared/schema:timeout-ms]])

(def ^:const usage "runner build [options]")

(def ^:const description "Prepare a renderer build with the selected Cargo features.")

(def cli-options
  [[nil "--[no-]default-features" "Enable Cargo defaults"]
   [nil "--features FEATURES" "Comma-separated functional Cargo features"
    :parse-fn shared/parse-feature-list]
   (shared/build-timeout-option "Complete preparation deadline")])

(defn resolve-options
  "Resolves build arguments and supplied flags."
  [options arguments]
  (shared/check-arguments arguments 0)
  options)

(defn check-options
  "Checks measurement feature policy and returns the resolved build options."
  [resolved]
  (selection/feature-selection {:default-features? (:default-features resolved)
                                :features (:features resolved)})
  resolved)
