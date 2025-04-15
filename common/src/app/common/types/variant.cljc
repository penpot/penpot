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

(defn add-new-prop
  "Adds a new property with generated name and provided value to the existing props list."
  [props value]
  (conj props {:name (str property-prefix (next-property-number props))
               :value value}))

(defn add-new-props
  "Adds new properties with generated names and provided values to the existing props list."
  [props values]
  (let [next-prop-num (next-property-number props)
        xf (map-indexed (fn [i v]
                          {:name (str property-prefix (+ next-prop-num i))
                           :value v}))]
    (into props xf values)))

(defn path-to-properties
  "From a list of properties and a name with path, assign each token of the
   path as value of a different property"
  ([path properties]
   (path-to-properties path properties 0))
  ([path properties min-props]
   (let [cpath          (cfh/split-path path)
         total-props    (max (count cpath) min-props)
         assigned       (mapv #(assoc % :value (nth cpath %2 "")) properties (range))
         ;; Add empty strings to the end of cpath to reach the minimum number of properties
         cpath          (take total-props (concat cpath (repeat "")))
         remaining      (drop (count properties) cpath)]
     (add-new-props assigned remaining))))


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

(defn remove-prefix
  "Removes the given prefix (with or without a trailing ' / ') from the beginning of the name"
  [name prefix]
  (let [long-name (str prefix " / ")]
    (cond
      (str/starts-with? name long-name)
      (subs name (count long-name))

      (str/starts-with? name prefix)
      (subs name (count prefix))

      :else
      name)))

(def ^:private xf:map-name
  (map :name))

(defn- matching-indices
  [props1 props2]
  (let [names-in-p2 (into #{} xf:map-name props2)
        xform (comp
               (map-indexed (fn [index {:keys [name]}]
                              (when (contains? names-in-p2 name)
                                index)))
               (filter some?))]
    (into #{} xform props1)))

(defn- find-index-by-name
  "Returns the index of the first item in props with the given name, or nil if not found."
  [name props]
  (some (fn [[idx item]]
          (when (= (:name item) name)
            idx))
        (map-indexed vector props)))

(defn- next-valid-position
  "Returns the first non-negative integer not present in the used-pos set."
  [used-pos]
  (loop [p 0]
    (if (contains? used-pos p)
      (recur (inc p))
      p)))

(defn- find-position
  "Returns the index of the property with the given name in `props`,
  or the next available index not in `used-pos` if not found."
  [name props used-pos]
  (or (find-index-by-name name props)
      (next-valid-position used-pos)))

(defn merge-properties
  "Merges props2 into props1 with the following rules:
    - For each property p2 in props2:
      - Skip it if its value is empty.
      - If props1 contains a property with the same name, update its value with that of p2.
      - Otherwise, assign p2's value to the first unused property in props1. A property is considered used if:
        - Its name exists in both props1 and props2, or
        - Its value has already been updated during the merge.
      - If no unused properties are available in props1, append a new property with a default name and p2's value."
  [props1 props2]
  (let [props2 (remove #(str/empty? (:value %)) props2)]
    (-> (reduce
         (fn [{:keys [props used-pos]} prop]
           (let [pos (find-position (:name prop) props used-pos)
                 used-pos (conj used-pos pos)]
             (if (< pos (count props))
               {:props (assoc-in (vec props) [pos :value] (:value prop)) :used-pos used-pos}
               {:props (add-new-prop props (:value prop)) :used-pos used-pos})))
         {:props (vec props1) :used-pos (matching-indices props1 props2)}
         props2)
        :props)))