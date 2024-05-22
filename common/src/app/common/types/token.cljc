;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.token
  (:require
   [app.common.schema :as sm]
   [app.common.schema.registry :as sr]
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

(def token-types
  #{:boolean
    :border-radius
    :stroke-width
    :box-shadow
    :dimension
    :numeric
    :opacity
    :other
    :rotation
    :sizing
    :spacing
    :string
    :typography})

(sm/def! ::token
  [:map {:title "Token"}
   [:id ::sm/uuid]
   [:name :string]
   [:type [::sm/one-of token-types]]
   [:value :any]
   [:description {:optional true} :string]
   [:modified-at {:optional true} ::sm/inst]])

(sm/def! ::border-radius
  [:map
   [:rx {:optional true} ::sm/uuid]
   [:ry {:optional true} ::sm/uuid]
   [:r1 {:optional true} ::sm/uuid]
   [:r2 {:optional true} ::sm/uuid]
   [:r3 {:optional true} ::sm/uuid]
   [:r4 {:optional true} ::sm/uuid]])

(def border-radius-keys (schema-keys ::border-radius))

(sm/def! ::stroke-width
  [:map
   [:stroke-width {:optional true} ::sm/uuid]])

(def stroke-width-keys (schema-keys ::stroke-width))

(sm/def! ::sizing
  [:map
   [:width {:optional true} ::sm/uuid]
   [:height {:optional true} ::sm/uuid]
   [:min-height {:optional true} ::sm/uuid]
   [:max-height {:optional true} ::sm/uuid]
   [:min-width {:optional true} ::sm/uuid]
   [:max-width {:optional true} ::sm/uuid]])

(def sizing-keys (schema-keys ::sizing))

(sm/def! ::opacity
  [:map
   [:opacity ::sm/uuid]])

(def opacity-keys (schema-keys ::opacity))

(sm/def! ::spacing
  [:map
   [:spacing-column {:optional true} ::sm/uuid]
   [:spacing-row {:optional true} ::sm/uuid]
   [:padding-p1 {:optional true} ::sm/uuid]
   [:padding-p2 {:optional true} ::sm/uuid]
   [:padding-p3 {:optional true} ::sm/uuid]
   [:padding-p4 {:optional true} ::sm/uuid]
   [:padding-all {:optional true} ::sm/uuid]
   [:position-x {:optional true} ::sm/uuid]
   [:position-y {:optional true} ::sm/uuid]])

(def spacing-keys (schema-keys ::spacing))

(sm/def! ::dimensions
  (merge-schemas ::sizing
                 ::spacing
                 ::stroke-width
                 ::border-radius))

(def dimensions-keys (schema-keys ::dimensions))

(sm/def! ::rotation
  [:map
   [:rotation ::sm/uuid]])

(def rotation-keys (schema-keys ::rotation))

(sm/def! ::tokens
  [:map {:title "Applied Tokens"
         :closed true}])

(sm/def! ::applied-tokens
  (merge-schemas ::tokens
                 ::border-radius
                 ::sizing
                 ::spacing
                 ::rotation
                 ::dimensions))
