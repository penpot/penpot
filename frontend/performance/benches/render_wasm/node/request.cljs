;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.request
  "Resolved benchmark requests, sender defaults and receiver validation."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.cli.ab :as ab]
   [benches.render-wasm.node.cli.build :as build]
   [benches.render-wasm.node.cli.compare :as compare]
   [benches.render-wasm.node.cli.console :as console]
   [benches.render-wasm.node.cli.help :as help]
   [benches.render-wasm.node.cli.merge :as merge]
   [benches.render-wasm.node.cli.purge :as purge]
   [benches.render-wasm.node.cli.run :as run]
   [malli.transform :as mt]))

(defn- command-schema
  [command options]
  [:map {:closed true}
   [:command [:= command]]
   [:console {:default {}} console/schema:console]
   [:options {:default {}} options]])

(def schema:request
  [:multi {:dispatch :command}
   ["build" (command-schema "build" build/schema:options)]
   ["run" (command-schema "run" run/schema:options)]
   ["ab" (command-schema "ab" ab/schema:options)]
   ["compare" (command-schema "compare" compare/schema:options)]
   ["merge" (command-schema "merge" merge/schema:options)]
   ["purge" (command-schema "purge" purge/schema:options)]
   ["help" (command-schema "help" help/schema:options)]])

(def ^:private default-decoder
  (delay (sm/decoder schema:request (mt/default-value-transformer))))

(defn apply-defaults
  "Fills contextual defaults for the selected command and build source."
  [partial-request]
  (@default-decoder partial-request))

(def check
  "Returns a complete valid request, or throws with schema explain data."
  (sm/check-fn schema:request
               :hint "invalid renderer benchmark request"
               :type ::invalid-request))
