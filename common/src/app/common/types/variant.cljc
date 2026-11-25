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

(def property-max-length 60)

(def schema:variant-property
  [:map
   [:name [:string {:max property-max-length}]]
   [:value [:string {:max property-max-length}]]])

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
(def value-prefix "Value ")

(defn normalize-property-text
  [value]
  (some-> value str/trim))

(def ^:private forbidden-property-chars-re #"[,=]")

(defn sanitize-property-text
  "Remove `,` and `=` from property name/value text, then trim.
   Non-strings (including nil) return nil — never call str/replace on them."
  [value]
  (when (string? value)
    (-> value
        (str/replace forbidden-property-chars-re "")
        normalize-property-text)))

(defn valid-property-name?
  [value]
  (let [value (normalize-property-text value)]
    (and (string? value)
         (not (str/blank? value))
         (nil? (re-find forbidden-property-chars-re value))
         (<= (count value) property-max-length))))

(defn valid-property-value?
  [value]
  (let [value (normalize-property-text value)]
    (and (string? value)
         (nil? (re-find forbidden-property-chars-re value))
         (<= (count value) property-max-length))))

(defn prepare-property-name
  "Sanitize then validate a property name. Returns the cleaned string or nil."
  [value]
  (let [value (sanitize-property-text value)]
    (when (valid-property-name? value)
      value)))

(defn prepare-property-value
  "Sanitize then validate a property value. nil becomes \"\".
   Other non-strings yield nil. Empty string remains valid."
  [value]
  (let [value (sanitize-property-text (if (nil? value) "" value))]
    (when (valid-property-value? value)
      value)))

(defn can-remove-property?
  [properties]
  (> (count properties) 1))

(defn variant-component?
  [component]
  (some? (:variant-id component)))

(defn variant-shape?
  [shape]
  (some? (:variant-id shape)))

(defn variant-container?
  [shape]
  (some? (:is-variant-container shape)))

(defn properties-to-name
  "Transform the properties into a name, with the values separated by comma, excluding the empty ones.

   Example:

   [{:name 'Property 1' :value 'Button'}
    {:name 'Property 2' :value 'Primary'}] -> 'Button, Primary'"
  [properties]
  (assert (or (sequential? properties) (nil? properties)))
  (->> properties
       (map :value)
       (remove str/empty?)
       (str/join ", ")))

(defn next-property-number
  "Returns the next property number, to avoid duplicates on the property names.

   Example:

   [{:name 'Property 1' :value 'x'}
    {:name 'Property 3' :value 'y'}] -> 4"
  [properties]
  (assert (or (sequential? properties) (nil? properties)))
  (let [numbers (keep
                 #(some->> (:name %) (re-find property-regex) second d/parse-integer)
                 properties)
        max-num (if (seq numbers)
                  (apply max numbers)
                  0)]
    (inc (max max-num (count properties)))))

(defn add-new-property
  "Adds a new property with generated name and provided value to the existing properties list.

   Example:

   [{:name 'Property 1' :value 'x'}] 'y' -> [{:name 'Property 1' :value 'x'}
                                             {:name 'Property 2' :value 'y'}]"
  [properties value]
  (assert (or (sequential? properties) (nil? properties)))
  (assert (or (string? value) (nil? value)))
  (conj properties {:name (str property-prefix (next-property-number properties))
                    :value value}))

(defn add-new-properties
  "Adds new properties with generated names and provided values to the existing properties list.

   Example:

   [{:name 'Property 1' :value 'x'}] ['a' 'b'] -> [{:name 'Property 1' :value 'x'}
                                                   {:name 'Property 2' :value 'a'}
                                                   {:name 'Property 3' :value 'b'}]"
  [properties values]
  (assert (or (sequential? properties) (nil? properties)))
  (assert (or (sequential? values) (nil? values)))
  (let [next-prop-num (next-property-number properties)
        xf (map-indexed (fn [i v]
                          {:name (str property-prefix (+ next-prop-num i))
                           :value v}))]
    (into properties xf values)))

(defn path-to-properties
  "From a list of properties and a name with path, assign each token of the
   path as value of a different property. It can add blank properties if
   necessary, until the min-properties number is reached.
  
   Example with min-properties=4:

   'Button / Primary / Hover' -> [{:name 'Property 1' :value 'Button'}
                                  {:name 'Property 2' :value 'Primary'}
                                  {:name 'Property 3' :value 'Hover'}
                                  {:name 'Property 4' :value ''}]"
  ([path properties]
   (path-to-properties path properties 0))
  ([path properties min-properties]
   (assert (or (string? path) (nil? path)))
   (assert (or (sequential? properties) (nil? properties)))
   (assert (int? min-properties))
   (let [cpath               (->> (cpn/split-path path)
                                  (mapv #(or (sanitize-property-text %) "")))
         total-properties    (max (count cpath) min-properties)
         assigned       (mapv #(assoc % :value (nth cpath %2 "")) properties (range))
         ;; Add empty strings to the end of cpath to reach the minimum number of properties
         cpath          (take total-properties (concat cpath (repeat "")))
         remaining      (drop (count properties) cpath)]
     (add-new-properties assigned remaining))))

(defn properties-map->formula
  "Transforms a map of properties to a formula of properties omitting the empty ones.

   Example:

   [{:name 'Property 1' :value 'Button'}
    {:name 'Property 2' :value 'Primary'}] -> 'Property 1=Button, Property 2=Primary'"
  [properties]
  (assert (or (sequential? properties) (nil? properties)))
  (->> properties
       (keep (fn [{:keys [name value]}]
               (when (not (str/blank? value))
                 (str name "=" value))))
       (str/join ", ")))

(defn properties-formula->map
  "Transforms a formula of properties to a map of properties.

   Example:

   'Property 1=Button, Property 2=Primary' -> [{:name 'Property 1' :value 'Button'}
                                               {:name 'Property 2' :value 'Primary'}]"
  [formula]
  (assert (or (string? formula) (nil? formula)))
  (->> (str/split formula ",")
       (mapv #(str/split % "=" 2))
       (filter (fn [[_ v]] (not (str/blank? v))))
       (mapv (fn [[k v]]
               {:name (str/trim k)
                :value (str/trim v)}))))

(defn valid-properties-formula?
  "Checks if a formula is valid.

   Example:

   'Property 1=Button, Property 2=Primary' -> true
   'Property 1=Button, Property 2' -> false"
  [formula]
  (assert (or (string? formula) (nil? formula)))
  (->> (str/split formula ",")
       (mapv #(str/split % "=" 2))
       (every? #(and (= 2 (count %))
                     (not (str/blank? (first %)))
                     (<= (count (first %)) property-max-length)
                     (<= (count (second %)) property-max-length)))))

(defn find-properties-to-remove
  "Compares two property maps to find which properties should be removed.

   Example:

   [{:name 'Property 1' :value 'x'}
    {:name 'Property 2' :value 'y'}]
   [{:name 'Property 1' :value 'x'}] -> [{:name 'Property 2' :value 'y'}]"
  [prev-properties upd-properties]
  (assert (or (sequential? prev-properties) (nil? prev-properties)))
  (assert (or (sequential? upd-properties) (nil? upd-properties)))
  (let [upd-names (set (map :name upd-properties))]
    (filterv #(not (contains? upd-names (:name %))) prev-properties)))

(defn find-properties-to-update
  "Compares two property maps to find which properties should be updated.

   Example:

   [{:name 'Property 1' :value 'x'}
    {:name 'Property 2' :value 'y'}]
   [{:name 'Property 1' :value 'new-x'}
    {:name 'Property 2' :value 'y'}] -> [{:name 'Property 1' :value 'new-x'}]"
  [prev-properties upd-properties]
  (assert (or (sequential? prev-properties) (nil? prev-properties)))
  (assert (or (sequential? upd-properties) (nil? upd-properties)))
  (filterv #(some (fn [prop] (and (= (:name %) (:name prop))
                                  (not= (:value %) (:value prop)))) prev-properties) upd-properties))

(defn find-properties-to-add
  "Compares two property maps to find which properties should be added.

   Example:

   [{:name 'Property 1' :value 'x'}]
   [{:name 'Property 1' :value 'x'}
    {:name 'Property 2' :value 'y'}] -> [{:name 'Property 2' :value 'y'}]"
  [prev-properties upd-properties]
  (assert (or (sequential? prev-properties) (nil? prev-properties)))
  (assert (or (sequential? upd-properties) (nil? upd-properties)))
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
   already present in a list, to avoid repetitions.

   Example:

   ['Property'] 'Property' -> 'Property (1)'
   ['Property' 'Property (1)'] 'Property' -> 'Property (2)'"
  [items item]
  (assert (or (sequential? items) (nil? items)))
  (assert (or (string? item) (nil? item)))
  (let [names      (group-numbers-by-base-name items)
        [base num] (split-base-name-and-number item)
        nums-taken (get names base #{})]
    (loop [n num]
      (if (nums-taken n)
        (recur (inc n))
        (str base (when (pos? n) (str " (" n ")")))))))

(defn update-number-in-repeated-prop-names
  "Add, keep or update a number for each prop name depending on the previous ones.

   Example:

   [{:name 'Property' :value 'x'}
    {:name 'Property' :value 'y'}] -> [{:name 'Property' :value 'x'}
                                        {:name 'Property (1)' :value 'y'}]"
  [properties]
  (assert (or (sequential? properties) (nil? properties)))
  (->> properties
       (reduce (fn [acc prop]
                 (conj acc {:name (update-number-in-repeated-item (mapv :name acc) (:name prop))
                            :value (:value prop)}))
               [])))

;; Sanitize stored property sets. Unlike `prepare-property-name` and
;; `prepare-property-value`, which reject invalid user input with nil,
;; these helpers always return valid text, so they can repair data.

(defn clip-property-text
  "Cut `text` to at most `max-length` characters."
  [text max-length]
  (if (> (count text) max-length)
    (subs text 0 max-length)
    text))

(defn- numbered-property-text
  "Join `base` and the ` (n)` suffix. Clip and trim `base` so the result
   fits in `property-max-length`, whatever the length of the suffix. An
   empty base yields \"(n)\"."
  [base n]
  (let [suffix (str " (" n ")")
        base   (-> base
                   (clip-property-text (- property-max-length (count suffix)))
                   str/trim)]
    (if (str/empty? base)
      (str "(" n ")")
      (str base suffix))))

(defn unique-property-text
  "Return `text`, trimmed and fitted in `property-max-length`. When the
   result is in `taken`, add or raise a ` (n)` suffix until it is not.
   A ` (n)` suffix is never cut: the base is clipped to leave room for it."
  [taken text]
  (let [text       (str/trim (or text ""))
        [base num] (split-base-name-and-number text)
        text       (if (and (pos? num) (> (count text) property-max-length))
                     (numbered-property-text base num)
                     (-> text
                         (clip-property-text property-max-length)
                         str/trim))
        taken      (set taken)]
    (if-not (contains? taken text)
      text
      (let [[base num] (split-base-name-and-number text)]
        (->> (iterate inc (max num 1))
             (map #(numbered-property-text base %))
             (d/seek #(not (contains? taken %))))))))

(defn sanitize-property-name
  "Return a valid property name for `name`, unique against the names in
   `taken`. Strip forbidden characters and trim. A blank or non-string
   name becomes a generated `Property N`."
  [name taken]
  (let [text (or (sanitize-property-text name) "")
        text (if (str/blank? text)
               (str property-prefix
                    (next-property-number (map (fn [name] {:name name}) taken)))
               text)]
    (unique-property-text taken text)))

(defn sanitize-property-value
  "Return a valid property value for `value`. Strip forbidden characters,
   trim and clip. A non-string value becomes \"\"."
  [value]
  (-> (or (sanitize-property-text value) "")
      (clip-property-text property-max-length)
      str/trim))

(defn sanitize-properties
  "Sanitize the name and value of every property in `props`, keeping
   names unique. Other keys of each property are kept."
  [props]
  (reduce (fn [acc prop]
            (conj acc (assoc prop
                             :name  (sanitize-property-name (:name prop) (map :name acc))
                             :value (sanitize-property-value (:value prop)))))
          []
          props))

(defn properties-names
  "Return every property name in `props-seq` (a seq of property vectors),
   without repeats, in the order they first appear."
  [props-seq]
  (into [] (comp cat (map :name) (distinct)) props-seq))

(defn align-properties
  "Return the properties of `props` in the order of `names`. A name with
   no property in `props` gets an empty value."
  [props names]
  (mapv (fn [name]
          (or (d/seek #(= name (:name %)) props)
              {:name name :value ""}))
        names))

(defn unique-values-tuple
  "Return `props` with a value combination that is not in `seen` (a seq of
   value vectors). On a clash, number the last non-blank value, or the
   first value when all are blank."
  [props seen]
  (let [seen (set seen)]
    (if (or (empty? props)
            (not (contains? seen (mapv :value props))))
      props
      (let [idx   (or (last (keep-indexed (fn [idx prop]
                                            (when-not (str/blank? (:value prop)) idx))
                                          props))
                      0)
            taken (map #(nth % idx "") seen)]
        (update-in props [idx :value] #(unique-property-text taken %))))))

(defn sanitize-properties-set
  "Sanitize the property vectors of all the variants of one set, given in
   layers-panel order (top first). Every vector gets the same names in the
   same order, and no two vectors share a value combination. Earlier
   vectors keep their values."
  [props-seq]
  (let [cleaned (mapv sanitize-properties props-seq)
        names   (properties-names cleaned)]
    (reduce (fn [acc props]
              (conj acc (unique-values-tuple (align-properties props names)
                                             (map #(mapv :value %) acc))))
            []
            cleaned)))

(defn find-index-for-property-name
  "Finds the index of a name in a property map.

   Example:

   [{:name 'Property 1' :value 'x'}
    {:name 'Property 2' :value 'y'}] 'Property 2' -> 1"
  [properties name]
  (assert (or (sequential? properties) (nil? properties)))
  (assert (or (string? name) (nil? name)))
  (some (fn [[idx prop]]
          (when (= (:name prop) name)
            idx))
        (map-indexed vector properties)))

(defn remove-prefix
  "Removes the given prefix (with or without a trailing ' / ') from the beginning of the name.

   Example:

   'Button / Primary' 'Button' -> 'Primary'
   'Button / Primary' 'Other' -> 'Button / Primary'"
  [name prefix]
  (assert (or (string? name) (nil? name)))
  (assert (or (string? prefix) (nil? prefix)))
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
      - If no unused properties are available in properties1, append a new property with a default name and p2's value.

   Example:

   [{:name 'Property 1' :value 'a'}
    {:name 'Property 2' :value 'b'}]
   [{:name 'Property 1' :value 'x'}
    {:name 'Property 2' :value 'y'}
    {:name 'Property 3' :value 'z'}] -> [{:name 'Property 1' :value 'x'}
                                          {:name 'Property 2' :value 'y'}
                                          {:name 'Property 3' :value 'z'}]"
  [properties1 properties2]
  (assert (or (sequential? properties1) (nil? properties1)))
  (assert (or (sequential? properties2) (nil? properties2)))
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

(defn group-properties-by-name
  "Group properties by :name, preserving first-seen name order.
   clojure.core/group-by loses order once maps grow past ArrayMap's
   8-key threshold."
  [props]
  (reduce (fn [m p]
            (update m (:name p) (fnil conj []) p))
          (d/ordered-map)
          props))

(defn compare-properties
  "Compares vectors of properties keeping the value if it is the same for all
   or setting a custom value where their values do not coincide.

   Example:

   [[{:name 'Property 1' :value 'x'}
     {:name 'Property 2' :value 'y'}]
    [{:name 'Property 1' :value 'x'}
     {:name 'Property 2' :value 'z'}]] -> [{:name 'Property 1' :value 'x'}
                                            {:name 'Property 2' :value nil}]"
  ([properties-list]
   (compare-properties properties-list nil))

  ([properties-list distinct-mark]
   (assert (or (sequential? properties-list) (nil? properties-list)))
   (assert (or (string? distinct-mark) (nil? distinct-mark)))
   (let [grouped (group-properties-by-name (apply concat properties-list))
         check-values (fn [values]
                        (let [vals (map :value values)]
                          (if (apply = vals)
                            (first vals)
                            distinct-mark)))]
     (mapv (fn [[name values]]
             {:name name :value (check-values values)})
           grouped))))

(defn properties-distance
  "Computes a weighted distance between two property lists `properties1` and `properties2`.
   Latter properties weight less that previous ones.

   Example:

   [{:name 'type' :value 'primary'}
    {:name 'status' :value 'default'}]
   [{:name 'type' :value 'primary'}
    {:name 'status' :value 'hover'}] -> 1.0"
  [properties1 properties2]
  (assert (or (sequential? properties1) (nil? properties1)))
  (assert (or (sequential? properties2) (nil? properties2)))
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
   the real name of the shape joined by the properties values separated by '/'.

   Example:

   {:name 'Button' :variant-name 'Primary, Hover'} -> 'Button / Primary / Hover'"
  [variant]
  (cpn/merge-path-item (:name variant) (str/replace (:variant-name variant) #", " " / ")))

(def ^:private boolean-pairs
  [["on" "off"]
   ["yes" "no"]
   ["true" "false"]])

(defn find-boolean-pair
  "Given a collection, return a map that contains the boolean equivalency if the values match
   with any of the boolean pairs. Returns nil if none match.

   Example:

   ['on' 'off'] -> {'on' true 'off' false}
   ['foo' 'bar'] -> nil"
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

(defn same-variant?
  "Determines if all elements belong to the same variant.

   Example:

   [{:variant-id 'abc'} {:variant-id 'abc'}] -> true
   [{:variant-id 'abc'} {:variant-id 'def'}] -> false"
  [components]
  (assert (or (sequential? components) (nil? components)))
  (let [variant-ids (distinct (map :variant-id components))
        not-blank?  (complement str/blank?)]
    (and
     (= 1 (count variant-ids))
     (not-blank? (first variant-ids)))))
