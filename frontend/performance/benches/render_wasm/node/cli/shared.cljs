;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.cli.shared
  "Value constraints and option groups shared by benchmark commands."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.options :as options]
   [benches.render-wasm.random :as random]
   [clojure.string :as str]))

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
   [:startup-timeout-ms {:default options/default-startup-timeout-ms} schema:timeout-ms]
   [:output-dir {:default nil} [:maybe ::sm/text]]
   [:screenshots {:default false} :boolean]
   [:require-gpu {:default false} :boolean]])

(def schema:comparison-permissions
  [:map {:closed true}
   [:allow-build-differences {:default false} :boolean]
   [:allow-scene-differences {:default false} :boolean]
   [:allow-all-differences {:default false} :boolean]])

(defn- parse-number
  [text]
  (if (str/blank? text) js/NaN (js/Number text)))

(defn number-option
  "Declares a numeric option with the given value constraint.
  Values accept JavaScript number syntax, including exponents, radix prefixes
  and surrounding whitespace."
  [flag description schema message]
  [nil flag description
   :parse-fn parse-number
   :validate [(sm/validator schema) message]])

(def output-option
  [nil "--output-dir DIR" "Explicit output home; must not exist"
   :validate [(sm/validator ::sm/text) "must be a nonblank path"]])

(def measurement-options
  [(number-option "--seed N" "Master scene seed" schema:seed
                  "must be a uint32 integer")
   [nil "--filter SUBSTR" "Select cases whose id contains SUBSTR"]
   (number-option "--warmups N" "Warmup attempts per case" schema:nonnegative-count
                  "must be a nonnegative JavaScript-safe integer")
   (number-option "--repetitions N" "Measured attempts per case" schema:positive-count
                  "must be a positive JavaScript-safe integer")
   (number-option "--timeout-ms MS" "Attempt deadline" schema:timeout-ms
                  "must be a positive integer within Node's timer range")
   (number-option "--startup-timeout-ms MS" "Host/browser readiness deadline" schema:timeout-ms
                  "must be a positive integer within Node's timer range")
   output-option
   [nil "--screenshots" "Capture one screenshot per case"]
   [nil "--require-gpu" "Fail when GPU rendering is unavailable"]])

(def comparison-options
  [[nil "--allow-build-differences" "Allow recorded build differences"]
   [nil "--allow-scene-differences" "Allow scene inputs and fingerprint content differences"]
   [nil "--allow-all-differences" "Allow all comparison compatibility differences"]])

(defn build-timeout-option
  "Declares a preparation deadline with the given description."
  [description]
  (number-option "--build-timeout-ms MS" description schema:timeout-ms
                 "must be a positive integer within Node's timer range"))

(defn check-arguments
  "Checks the number of positional arguments."
  [arguments expected]
  (when-not (= expected (count arguments))
    (throw (ex-info (str "expected " expected " positional "
                         (if (= expected 1) "argument" "arguments")
                         ", received " (count arguments))
                    {:type ::invalid-args :arguments arguments}))))
