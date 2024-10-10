;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.token
  (:require
   [app.common.schema :as sm]
   [app.common.schema.registry :as sr]
   [clojure.set :as set]
   [malli.util :as mu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn merge-schemas
  "Merge registered schemas."
  [& schema-keys]
  (let [schemas (map #(get @sr/registry %) schema-keys)]
    (reduce sm/merge schemas)))

(defn schema-keys
  "Converts registed map schema into set of keys."
  [registered-schema]
  (->> (get @sr/registry registered-schema)
       (sm/schema)
       (mu/keys)
       (into #{})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def token-type->dtcg-token-type
  {:boolean       "boolean"
   :border-radius "borderRadius"
   :box-shadow    "boxShadow"
   :color         "color"
   :dimensions    "dimension"
   :numeric       "numeric"
   :opacity       "opacity"
   :other         "other"
   :rotation      "rotation"
   :sizing        "sizing"
   :spacing       "spacing"
   :string        "string"
   :stroke-width  "strokeWidth"})

(def dtcg-token-type->token-type
  (set/map-invert token-type->dtcg-token-type))

(def token-types
  (into #{} (keys token-type->dtcg-token-type)))

(defn valid-token-type?
  [t]
  (token-types t))

(def token-name-ref :string)

(defn valid-token-name-ref?
  [n]
  (string? n))

;; TODO Move this to tokens-lib
(sm/register! ::token
  [:map {:title "Token"}
   [:name token-name-ref]
   [:type [::sm/one-of token-types]]
   [:value :any]
   [:description {:optional true} [:maybe :string]]
   [:modified-at {:optional true} ::sm/inst]])

(sm/register! ::color
  [:map
   [:color {:optional true} token-name-ref]])

(def color-keys (schema-keys ::color))

(sm/register! ::border-radius
  [:map
   [:rx {:optional true} token-name-ref]
   [:ry {:optional true} token-name-ref]
   [:r1 {:optional true} token-name-ref]
   [:r2 {:optional true} token-name-ref]
   [:r3 {:optional true} token-name-ref]
   [:r4 {:optional true} token-name-ref]])

(def border-radius-keys (schema-keys ::border-radius))

(sm/register! ::stroke-width
  [:map
   [:stroke-width {:optional true} token-name-ref]])

(def stroke-width-keys (schema-keys ::stroke-width))

(sm/register! ::sizing
  [:map
   [:width {:optional true} token-name-ref]
   [:height {:optional true} token-name-ref]
   [:layout-item-min-w {:optional true} token-name-ref]
   [:layout-item-max-w {:optional true} token-name-ref]
   [:layout-item-min-h {:optional true} token-name-ref]
   [:layout-item-max-h {:optional true} token-name-ref]])

(def sizing-keys (schema-keys ::sizing))

(sm/register! ::opacity
  [:map
   [:opacity {:optional true} token-name-ref]])

(def opacity-keys (schema-keys ::opacity))

(sm/register! ::spacing
  [:map
   [:row-gap {:optional true} token-name-ref]
   [:column-gap {:optional true} token-name-ref]
   [:p1 {:optional true} token-name-ref]
   [:p2 {:optional true} token-name-ref]
   [:p3 {:optional true} token-name-ref]
   [:p4 {:optional true} token-name-ref]
   [:x {:optional true} token-name-ref]
   [:y {:optional true} token-name-ref]])

(def spacing-keys (schema-keys ::spacing))

(sm/register! ::dimensions
  (merge-schemas ::sizing
                 ::spacing
                 ::stroke-width
                 ::border-radius))

(def dimensions-keys (schema-keys ::dimensions))

(sm/register! ::rotation
  [:map
   [:rotation {:optional true} token-name-ref]])

(def rotation-keys (schema-keys ::rotation))

(sm/register! ::tokens
  [:map {:title "Applied Tokens"}])

(sm/register! ::applied-tokens
  (merge-schemas ::tokens
                 ::border-radius
                 ::sizing
                 ::spacing
                 ::rotation
                 ::dimensions))
