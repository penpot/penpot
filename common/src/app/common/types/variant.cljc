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
  ;; A component that is part of a variant set.
  [:map
   [:variant-id {:optional true} ::sm/uuid]
   [:variant-properties {:optional true} [:vector schema:variant-property]]])

(def schema:variant-shape
  ;; The root shape of the main instance of a variant component.
  [:map
   [:variant-id {:optional true} ::sm/uuid]
   [:variant-name {:optional true} :string]])

(def schema:variant-container
  ;; is a board that contains all variant components of a variant set,
  ;; for grouping them visually in the workspace.
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
(def value-prefix "Value ")


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


(defn properties-map-to-string
  "Transforms a map of properties to a string of properties omitting the empty ones"
  [properties]
  (->> properties
       (keep (fn [{:keys [name value]}]
               (when (not (str/blank? value))
                 (str name "=" value))))
       (str/join ", ")))


(defn properties-string-to-map
  "Transforms a string of properties to a map of properties"
  [s]
  (->> (str/split s ",")
       (mapv #(str/split % "="))
       (mapv (fn [[k v]]
               {:name (str/trim k)
                :value (str/trim v)}))))


(defn valid-properties-string?
  "Checks if a string of properties has a processable format or not"
  [s]
  (let [pattern #"^([a-zA-Z0-9\s]+=[a-zA-Z0-9\s]+)(,\s*[a-zA-Z0-9\s]+=[a-zA-Z0-9\s]+)*$"]
    (not (nil? (re-matches pattern s)))))


(defn find-properties-to-remove
  "Compares two property maps to find which properties should be removed"
  [prev-props upd-props]
  (let [upd-names (set (map :name upd-props))]
    (filterv #(not (contains? upd-names (:name %))) prev-props)))


(defn find-properties-to-update
  "Compares two property maps to find which properties should be updated"
  [prev-props upd-props]
  (filterv #(some (fn [prop] (and (= (:name %) (:name prop))
                                  (not= (:value %) (:value prop)))) prev-props) upd-props))


(defn find-properties-to-add
  "Compares two property maps to find which properties should be added"
  [prev-props upd-props]
  (let [prev-names (set (map :name prev-props))]
    (filterv #(not (contains? prev-names (:name %))) upd-props)))


(defn find-index-for-property-name
  "Finds the index of a name in a property map"
  [props name]
  (some (fn [[idx prop]]
          (when (= (:name prop) name)
            idx))
        (map-indexed vector props)))
