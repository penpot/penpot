;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.cli.shared
  "Value constraints and option groups shared by benchmark commands."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.options :as options]
   [benches.render-wasm.random :as random]))

(def schema:nonnegative-count
  [:int {:min 0 :max js/Number.MAX_SAFE_INTEGER}])

(def schema:positive-count
  [:int {:min 1 :max js/Number.MAX_SAFE_INTEGER}])

(def schema:timeout-ms
  "Positive integer milliseconds within Node's timer range."
  [:int {:min 1 :max 2147483647}])

(def schema:seed
  [:fn {:error/message "expected a uint32 seed"} random/seed?])

(def schema:build-id
  [:fn {:error/message "expected a benchmark artifact ID"} artifacts/valid-id?])

(def schema:feature
  [:fn {:error/message "expected a Cargo feature name"} options/feature-name?])

(def schema:measurement
  [:map {:closed true}
   [:seed {:default 42} schema:seed]
   [:filter {:default nil} [:maybe :string]]
   [:warmups {:default 3} schema:nonnegative-count]
   [:repetitions {:default 10} schema:positive-count]
   [:timeout-ms {:default 30000} schema:timeout-ms]
   [:startup-timeout-ms {:default 30000} schema:timeout-ms]
   [:output-dir {:default nil} [:maybe ::sm/text]]
   [:screenshots {:default false} :boolean]
   [:require-gpu {:default false} :boolean]])

(def schema:comparison-permissions
  [:map {:closed true}
   [:allow-build-differences {:default false} :boolean]
   [:allow-scene-differences {:default false} :boolean]
   [:allow-all-differences {:default false} :boolean]])
