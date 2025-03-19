;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.variant
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:variant-property
  [:map
   [:name :string]
   [:value :string]])

(def schema:variant-component
  [:map
   [:variant-id {:optional true} ::sm/uuid]
   [:variant-properties {:optional true} [:vector schema:variant-property]]])

(def schema:variant-shape
  [:map
   [:variant-id {:optional true} ::sm/uuid]
   [:variant-name {:optional true} :string]])

(def schema:variant-container
  [:map
   [:is-variant-container {:optional true} :boolean]])

(sm/register! ::variant-property schema:variant-property)
(sm/register! ::variant-component schema:variant-component)
(sm/register! ::variant-shape schema:variant-shape)
(sm/register! ::variant-container schema:variant-container)

(def valid-variant-component?
  (sm/check-fn schema:variant-component))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def property-prefix "Property")
(def property-regex (re-pattern (str property-prefix "(\\d+)")))
(def value-prefix "Value")


(defn properties-to-name
  "Transform the properties into a name, with the values separated by comma"
  [properties]
  (->> properties
       (map :value)
       (remove str/empty?)
       (str/join ", ")))


(defn next-property-number
  "Returns the next property number, to avoid duplicates on the property names"
  [properties]
  (let [numbers (keep
                 #(some->> (:name %) (re-find property-regex) second d/parse-integer)
                 properties)
        max-num (if (seq numbers)
                  (apply max numbers)
                  0)]
    (inc (max max-num (count properties)))))


(defn path-to-properties
  "From a list of properties and a name with path, assign each token of the
   path as value of a different property"
  [path properties]
  (let [next-prop-num  (next-property-number properties)
        cpath          (cfh/split-path path)
        assigned       (mapv #(assoc % :value (nth cpath %2 "")) properties (range))
        remaining      (drop (count properties) cpath)
        new-properties (map-indexed (fn [i v] {:name (str property-prefix (+ next-prop-num i))
                                               :value v}) remaining)]
    (into assigned new-properties)))
