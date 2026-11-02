;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.help
  "Options for benchmark CLI help."
  (:require
   [benches.render-wasm.node.cli.registry :as registry]))

(def schema:options
  [:map {:closed true}
   [:topic {:default nil}
    [:maybe (into [:enum] registry/command-names)]]])

(def ^:const usage "runner COMMAND [options]")

(def ^:const description
  "Renderer benchmarks. Use COMMAND --help for command options.
Numeric options accept JavaScript number syntax, including exponents, radix
prefixes and surrounding whitespace, within each option's integer bounds.")

(def cli-options
  [["-h" "--help" "Print help"]])
