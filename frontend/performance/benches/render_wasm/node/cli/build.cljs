;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.build
  "Options for preparing a benchmark build."
  (:require
   [benches.render-wasm.node.cli.shared :as shared]
   [benches.render-wasm.node.options :as options]))

(def schema:options
  [:map {:closed true}
   [:default-features {:default true} :boolean]
   [:features {:default []} [:vector shared/schema:feature]]
   [:build-timeout-ms {:default options/default-build-timeout-ms} shared/schema:timeout-ms]])

(def ^:const usage "runner build [options]")

(def ^:const description "Prepare a renderer build with the selected Cargo features.")

(def cli-options
  [[nil "--[no-]default-features" "Enable Cargo defaults"]
   [nil "--features FEATURES" "Comma-separated functional Cargo features"
    :parse-fn options/parse-feature-list]
   (shared/build-timeout-option "Complete preparation deadline")])

(defn resolve-options
  "Resolves build arguments and supplied flags."
  [options arguments]
  (shared/check-arguments arguments 0)
  options)

(defn check-options
  "Checks measurement feature policy and returns the resolved build options."
  [resolved]
  (options/feature-selection {:default-features? (:default-features resolved)
                              :features (:features resolved)})
  resolved)
