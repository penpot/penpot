;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.text.content.styles
  (:require
   [app.common.transit :as transit]
   [app.common.types.text :as txt]
   [cuerdas.core :as str]))

(defn encode
  [value]
  (transit/encode-str value))

(defn decode
  [value]
  (if (= value "")
    nil
    (transit/decode-str value)))

(def mapping
  {:fills [encode decode]
   :ruby [identity #(when-not (= "" %) %)]
   :ruby-size [identity #(when-not (= "" %) %)]
   :ruby-align [identity #(when-not (= "" %) %)]
   :ruby-overhang [identity #(when-not (= "" %) %)]
   :ruby-side [identity #(when-not (= "" %) %)]
   :text-emphasis [identity #(when-not (= "" %) %)]
   :warichu [identity #(when-not (= "" %) %)]
   :font-features [identity #(when-not (= "" %) %)]
   :annotation-clearance [identity #(when-not (= "" %) %)]
   :typography-ref-id [encode decode]
   :typography-ref-file [encode decode]
   :font-id [identity identity]
   :font-variant-id [identity identity]
   :vertical-align [identity identity]})

(defn- text-combine-upright->css
  [value]
  (case value
    "digits2" "digits 2"
    "digits3" "digits 3"
    "digits" "digits 4"
    value))

(defn- css->text-combine-upright
  [value]
  (case value
    "digits 2" "digits2"
    "digits 3" "digits3"
    "digits 4" "digits"
    value))

(defn normalize-style-value
  "This function adds units to style values"
  [k v]
  (cond
    (and (keyword? k)
         (or (= k :font-size)
             (= k :letter-spacing))
         (not= (str/slice v -2) "px"))
    (str v "px")

    (and (= k :font-family) (seq v))
    ;; pick just first family, avoid quoting twice, and add var(--fallback-families)
    (str/concat (str/quote (str/unquote (first (str/split v ",")))) ", var(--fallback-families)")

    :else
    v))

(defn normalize-attr-value
  "This function strips units from attr values and un-scapes font-family"
  [k v]
  (cond
    (= v "mixed")
    :multiple

    (and (or (= k :font-size)
             (= k :letter-spacing))
         (= (str/slice v -2) "px"))
    (str/slice v 0 -2)

    (= k :font-family)
    (str/unquote (str/replace v ", var(--fallback-families)" ""))

    :else
    v))

(defn get-style-name-as-css-variable
  [key]
  (str/concat "--" (name key)))

(defn get-style-name
  [key]
  (cond
    (= key :text-direction)
    "direction"

    :else
    (name key)))

(defn get-style-keyword
  [key]
  (keyword (get-style-name-as-css-variable key)))

(defn get-attr-keyword-from-css-variable
  [style-name]
  (keyword (str/slice style-name 2)))

(defn get-attr-keyword
  [style-name]
  (cond
    (= style-name "direction")
    :text-direction

    :else
    (keyword style-name)))

(defn attr-needs-mapping?
  [key]
  (let [contained? (contains? mapping key)]
    contained?))

(defn attr->style-key
  [key]
  (if (attr-needs-mapping? key)
    (let [name (get-style-name-as-css-variable key)]
      (keyword name))
    (cond
      (= key :text-direction)
      (keyword "direction")

      :else
      key)))

(defn attr->style-value
  ([key value]
   (attr->style-value key value false))
  ([key value normalize?]
   (cond
     (= key :text-combine-upright)
     (text-combine-upright->css value)

     (attr-needs-mapping? key)
     (let [[encoder] (get mapping key)]
       (if normalize?
         (normalize-style-value key (encoder value))
         (encoder value)))

     normalize?
     (normalize-style-value key value)

     :else
     value)))

(defn attr->style
  [[key value]]
  [(attr->style-key key)
   (attr->style-value key value)])

(defn attrs->styles
  "Maps attrs to styles"
  [styles]
  (let [mapped-styles
        (into {} (comp (filter (fn [[_ v]] (some? v)))
                       (map attr->style))
              styles)]
    (clj->js mapped-styles)))

(defn style-needs-mapping?
  [name]
  (str/starts-with? name "--"))

(defn style->attr-key
  [key]
  (if (style-needs-mapping? key)
    (keyword (str/slice key 2))
    (keyword key)))

(defn style->attr-value
  ([name value]
   (style->attr-value name value false))
  ([name value normalize?]
   (cond
     (= name "text-combine-upright")
     (css->text-combine-upright value)

     (style-needs-mapping? name)
     (let [key (get-attr-keyword-from-css-variable name)
           [_ decoder] (get mapping key)]
       (if normalize?
         (normalize-attr-value key (decoder value))
         (decoder value)))

     :else
     (let [key (get-attr-keyword name)]
       (if normalize?
         (normalize-attr-value key value)
         value)))))

(defn style->attr
  "Maps style to attr"
  [[key value]]
  [(style->attr-key key)
   (style->attr-value key value)])

(defn styles->attrs
  "Maps styles to attrs"
  [styles]
  (let [mapped-attrs
        (into {} (map style->attr styles))]
    mapped-attrs))

(defn get-style-defaults
  "Returns a Javascript object compatible with the TextEditor default styles"
  [style-defaults]
  (clj->js
   (reduce
    (fn [acc [k v]]
      (if (contains? mapping k)
        (let [[style-encode] (get mapping k)
              style-name (get-style-name-as-css-variable k)
              style-value (normalize-style-value style-name (style-encode v))]
          (assoc acc style-name style-value))
        (let [style-name (get-style-name k)
              style-value (normalize-style-value style-name v)]
          (assoc acc style-name style-value)))) {} style-defaults)))

(def mixed-values #{:mixed :multiple "mixed" "multiple"})

(defn get-styles-from-style-declaration
  "Returns a ClojureScript object compatible with text nodes"
  [style-declaration & {:keys [removed-mixed] :or {removed-mixed false}}]
  (reduce
   (fn [acc k]
     (if (contains? mapping k)
       (let [style-name (get-style-name-as-css-variable k)
             [_ style-decode] (get mapping k)
             style-value (.getPropertyValue style-declaration style-name)]
         (if (or (not removed-mixed) (not (contains? mixed-values style-value)))
           (assoc acc k (style-decode style-value))
           acc))
       (let [style-name (get-style-name k)
             style-value (.getPropertyValue style-declaration style-name)
             style-value (if (= k :text-combine-upright)
                           (css->text-combine-upright style-value)
                           (normalize-attr-value k style-value))]
         (if (or (not removed-mixed) (not (contains? mixed-values style-value)))
           (assoc acc k style-value)
           acc)))) {} txt/text-style-attrs))

(defn get-styles-from-event
  "Returns a ClojureScript object compatible with text nodes"
  [e]
  (let [style-declaration (.-detail e)]
    (get-styles-from-style-declaration style-declaration)))
