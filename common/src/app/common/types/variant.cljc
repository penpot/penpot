;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.variant
  (:require
   [app.common.data :as d]
   [app.common.math :as math]
   [app.common.path-names :as cpn]
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
  "A component that is part of a variant set"
  [:map
   [:variant-id {:optional true} ::sm/uuid]
   [:variant-properties {:optional true} [:vector schema:variant-property]]])

(def valid-variant-component?
  (sm/check-fn schema:variant-component))

(def schema:variant-shape
  "The root shape of the main instance of a variant component"
  [:map
   [:variant-id {:optional true} ::sm/uuid]
   [:variant-name {:optional true} :string]
   [:variant-error {:optional true} :string]])

(def valid-variant-shape?
  (sm/check-fn schema:variant-shape))

(def schema:variant-container
  "Is a board that contains all variant components of a variant set,
  for grouping them visually in the workspace"
  [:map
   [:is-variant-container {:optional true} :boolean]])

(def valid-variant-container?
  (sm/check-fn schema:variant-container))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def property-prefix "Property ")
(def property-regex (re-pattern (str property-prefix "(\\d+)")))
(def property-max-length 60)
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

(defn add-new-property
  "Adds a new property with generated name and provided value to the existing properties list."
  [properties value]
  (conj properties {:name (str property-prefix (next-property-number properties))
               :value value}))

(defn add-new-properties
  "Adds new properties with generated names and provided values to the existing properties list."
  [properties values]
  (let [next-prop-num (next-property-number properties)
        xf (map-indexed (fn [i v]
                          {:name (str property-prefix (+ next-prop-num i))
                           :value v}))]
    (into properties xf values)))

(defn path-to-properties
  "From a list of properties and a name with path, assign each token of the
   path as value of a different property"
  ([path properties]
   (path-to-properties path properties 0))
  ([path properties min-properties]
   (let [cpath          (cpn/split-path path)
         total-properties    (max (count cpath) min-properties)
         assigned       (mapv #(assoc % :value (nth cpath %2 "")) properties (range))
         ;; Add empty strings to the end of cpath to reach the minimum number of properties
         cpath          (take total-properties (concat cpath (repeat "")))
         remaining      (drop (count properties) cpath)]
     (add-new-properties assigned remaining))))

(defn properties-map->formula
  "Transforms a map of properties to a formula of properties omitting the empty ones"
  [properties]
  (->> properties
       (keep (fn [{:keys [name value]}]
               (when (not (str/blank? value))
                 (str name "=" value))))
       (str/join ", ")))

(defn properties-formula->map
  "Transforms a formula of properties to a map of properties"
  [s]
  (->> (str/split s ",")
       (mapv #(str/split % "=" 2))
       (filter (fn [[_ v]] (not (str/blank? v))))
       (mapv (fn [[k v]]
               {:name (str/trim k)
                :value (str/trim v)}))))

(defn valid-properties-formula?
  "Checks if a formula is valid"
  [s]
  (->> (str/split s ",")
       (mapv #(str/split % "=" 2))
       (every? #(and (= 2 (count %))
                     (not (str/blank? (first %)))
                     (< (count (first %)) property-max-length)
                     (< (count (second %)) property-max-length)))))

(defn find-properties-to-remove
  "Compares two property maps to find which properties should be removed"
  [prev-properties upd-properties]
  (let [upd-names (set (map :name upd-properties))]
    (filterv #(not (contains? upd-names (:name %))) prev-properties)))

(defn find-properties-to-update
  "Compares two property maps to find which properties should be updated"
  [prev-properties upd-properties]
  (filterv #(some (fn [prop] (and (= (:name %) (:name prop))
                                  (not= (:value %) (:value prop)))) prev-properties) upd-properties))

(defn find-properties-to-add
  "Compares two property maps to find which properties should be added"
  [prev-properties upd-properties]
  (let [prev-names (set (map :name prev-properties))]
    (filterv #(not (contains? prev-names (:name %))) upd-properties)))

(defn- split-base-name-and-number
  "Extract the number in parentheses from an item, if present, and return both the base name and the number"
  [item]
  (let [pattern-num-parens #"\(\d+\)$"
        pattern-num        #"\d+"
        base (-> item (str/replace pattern-num-parens "") (str/trim))
        num  (some->> item (re-find pattern-num-parens) (re-find pattern-num) (d/parse-integer))]
    [base (d/nilv num 0)]))

(defn- group-numbers-by-base-name
  "Return a map with a set of numbers associated to each base name"
  [items]
  (reduce (fn [acc item]
            (let [[base num] (split-base-name-and-number item)]
              (update acc base (fnil conj #{}) num)))
          {}
          items))

(defn update-number-in-repeated-item
  "Add, keep or update a number in parentheses for a given item, if necessary, depending on the items
   already present in a list, to avoid repetitions"
  [items item]
  (let [names      (group-numbers-by-base-name items)
        [base num] (split-base-name-and-number item)
        nums-taken (get names base #{})]
    (loop [n num]
      (if (nums-taken n)
        (recur (inc n))
        (str base (when (pos? n) (str " (" n ")")))))))

(defn update-number-in-repeated-prop-names
  "Add, keep or update a number for each prop name depending on the previous ones"
  [properties]
  (->> properties
       (reduce (fn [acc prop]
                 (conj acc {:name (update-number-in-repeated-item (mapv :name acc) (:name prop))
                            :value (:value prop)}))
               [])))

(defn find-index-for-property-name
  "Finds the index of a name in a property map"
  [properties name]
  (some (fn [[idx prop]]
          (when (= (:name prop) name)
            idx))
        (map-indexed vector properties)))

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
  [properties1 properties2]
  (let [names-in-p2 (into #{} xf:map-name properties2)
        xform (comp
               (map-indexed (fn [index {:keys [name]}]
                              (when (contains? names-in-p2 name)
                                index)))
               (filter some?))]
    (into #{} xform properties1)))

(defn- find-index-by-name
  "Returns the index of the first item in properties with the given name, or nil if not found."
  [name properties]
  (some (fn [[idx item]]
          (when (= (:name item) name)
            idx))
        (map-indexed vector properties)))

(defn- next-valid-position
  "Returns the first non-negative integer not present in the used-pos set."
  [used-pos]
  (loop [p 0]
    (if (contains? used-pos p)
      (recur (inc p))
      p)))

(defn- find-position
  "Returns the index of the property with the given name in `properties`,
  or the next available index not in `used-pos` if not found."
  [name properties used-pos]
  (or (find-index-by-name name properties)
      (next-valid-position used-pos)))

(defn merge-properties
  "Merges properties2 into properties1 with the following rules:
    - For each property p2 in properties2:
      - Skip it if its value is empty.
      - If properties1 contains a property with the same name, update its value with that of p2.
      - Otherwise, assign p2's value to the first unused property in properties1. A property is considered used if:
        - Its name exists in both properties1 and properties2, or
        - Its value has already been updated during the merge.
      - If no unused properties are available in properties1, append a new property with a default name and p2's value."
  [properties1 properties2]
  (let [properties2 (remove #(str/empty? (:value %)) properties2)]
    (-> (reduce
         (fn [{:keys [properties used-pos]} prop]
           (let [pos (find-position (:name prop) properties used-pos)
                 used-pos (conj used-pos pos)]
             (if (< pos (count properties))
               {:properties (assoc-in (vec properties) [pos :value] (:value prop)) :used-pos used-pos}
               {:properties (add-new-property properties (:value prop)) :used-pos used-pos})))
         {:properties (vec properties1) :used-pos (matching-indices properties1 properties2)}
         properties2)
        :properties)))

(defn compare-properties
  "Compares vectors of properties keeping the value if it is the same for all
   or setting a custom value where their values do not coincide"
  ([properties-list]
   (compare-properties properties-list nil))

  ([properties-list distinct-mark]
   (let [grouped (group-by :name (apply concat properties-list))
         check-values (fn [values]
                        (let [vals (map :value values)]
                          (if (apply = vals)
                            (first vals)
                            distinct-mark)))]
     (mapv (fn [[name values]]
             {:name name :value (check-values values)})
           grouped))))

(defn same-variant?
  "Determines if all elements belong to the same variant"
  [components]
  (let [variant-ids (distinct (map :variant-id components))
        not-blank?  (complement str/blank?)]
    (and
     (= 1 (count variant-ids))
     (not-blank? (first variant-ids)))))

(defn distance
  "Computes a weighted distance between two property lists `properties1` and `properties2`.
   Latter properties weight less that previous ones"
  [properties1 properties2]
  (let [total-num-properties (count properties1)
        xform           (map-indexed
                         (fn [idx [p1 p2]]
                           (if (not= p1 p2)
                             (math/pow 2 (- total-num-properties idx))
                             0)))]
    (transduce
     xform
     +
     (map vector properties1 properties2))))

(defn variant-name-to-name
  "Transforms a variant-name (its properties values) into a standard name:
   the real name of the shape joined by the properties values separated by '/'"
  [variant]
  (cpn/merge-path-item (:name variant) (str/replace (:variant-name variant) #", " " / ")))

(def ^:private boolean-pairs
  [["on" "off"]
   ["yes" "no"]
   ["true" "false"]])

(defn find-boolean-pair
  "Given a vector, return a map that contains the boolean equivalency if the values match
   with any of the boolean pairs. Returns nil if none match."
  [[a b :as v]]
  (let [a' (-> a str/trim str/lower)
        b' (-> b str/trim str/lower)]
    (when (= (count v) 2)
      (some (fn [[t f]]
              (cond (and (= a' t)
                         (= b' f)) {a true b false}
                    (and (= b' t)
                         (= a' f)) {b true a false}
                    :else nil))
            boolean-pairs))))
