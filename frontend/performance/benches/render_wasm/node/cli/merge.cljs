;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.cli.merge
  "Options for merging saved benchmark records."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.cli.shared :as shared]))

(def schema:options
  [:map {:closed true}
   [:base-path ::sm/text]
   [:additional-path ::sm/text]
   [:output-dir {:default nil} [:maybe ::sm/text]]])

(def ^:const usage "runner merge BASE ADDITIONAL [options]")

(def ^:const description
  "Merge two saved records. Paths resolve from the caller's working directory.")

(def cli-options
  [shared/output-option])

(defn resolve-options
  "Resolves two literal paths and the supplied output directory."
  [options arguments]
  (shared/check-arguments arguments 2)
  (assoc options :base-path (first arguments) :additional-path (second arguments)))
