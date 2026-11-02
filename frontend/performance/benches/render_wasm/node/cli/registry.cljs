;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.registry
  "Ordered command definitions for parsing, request schemas and help."
  (:require
   [benches.render-wasm.node.cli.ab :as ab]
   [benches.render-wasm.node.cli.build :as build]
   [benches.render-wasm.node.cli.compare :as compare]
   [benches.render-wasm.node.cli.merge :as merge]
   [benches.render-wasm.node.cli.purge :as purge]
   [benches.render-wasm.node.cli.run :as run]))

(def commands
  [["build" {:usage build/usage :description build/description
             :options build/cli-options :resolve build/resolve-options :schema build/schema:options}]
   ["run" {:usage run/usage :description run/description
           :options run/cli-options :resolve run/resolve-options :schema run/schema:options
           :help-options {:build {:kind :prepare}} :help-defaults run/get-help-defaults}]
   ["ab" {:usage ab/usage :description ab/description
          :options ab/cli-options :resolve ab/resolve-options :schema ab/schema:options
          :check ab/check-options}]
   ["compare" {:usage compare/usage :description compare/description
               :options compare/cli-options :resolve compare/resolve-options :schema compare/schema:options}]
   ["merge" {:usage merge/usage :description merge/description
             :options merge/cli-options :resolve merge/resolve-options :schema merge/schema:options}]
   ["purge" {:usage purge/usage :description purge/description
             :options purge/cli-options :resolve purge/resolve-options :schema purge/schema:options}]])

(def command-names (mapv first commands))
