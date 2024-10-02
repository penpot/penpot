;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.content.styles
  (:require
   [app.common.text :as txt]
   [app.common.transit :as transit]
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
   :typography-ref-id [encode decode]
   :typography-ref-file [encode decode]
   :font-id [identity identity]
   :font-variant-id [identity identity]
   :vertical-align [identity identity]})

(defn normalize-style-value
  "This function adds units to style values"
  [k v]
  (cond
    (and (or (= k :font-size)
             (= k :letter-spacing))
         (not= (str/slice v -2) "px"))
    (str v "px")

    :else
    v))

(defn normalize-attr-value
  "This function strips units from attr values"
  [k v]
  (cond
    (and (or (= k :font-size)
             (= k :letter-spacing))
         (= (str/slice v -2) "px"))
    (str/slice v 0 -2)

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
   (if (attr-needs-mapping? key)
     (let [[encoder] (get mapping key)]
       (if normalize?
         (normalize-style-value key (encoder value))
         (encoder value)))
     (if normalize?
       (normalize-style-value key value)
       value))))

(defn attr->style
  [[key value]]
  [(attr->style-key key)
   (attr->style-value key value)])

(defn attrs->styles
  "Maps attrs to styles"
  [styles]
  (let [mapped-styles
        (into {} (map attr->style styles))]
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
   (if (style-needs-mapping? name)
     (let [key (get-attr-keyword-from-css-variable name)
           [_ decoder] (get mapping key)]
       (if normalize?
         (normalize-attr-value key (decoder value))
         (decoder value)))
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

(defn get-styles-from-style-declaration
  "Returns a ClojureScript object compatible with text nodes"
  [style-declaration]
  (reduce
   (fn [acc k]
     (if (contains? mapping k)
       (let [style-name (get-style-name-as-css-variable k)
             [_ style-decode] (get mapping k)
             style-value (.getPropertyValue style-declaration style-name)]
         (assoc acc k (style-decode style-value)))
       (let [style-name (get-style-name k)
             style-value (normalize-attr-value k (.getPropertyValue style-declaration style-name))]
         (assoc acc k style-value)))) {} txt/text-style-attrs))

(defn get-styles-from-event
  "Returns a ClojureScript object compatible with text nodes"
  [e]
  (let [style-declaration (.-detail e)]
    (get-styles-from-style-declaration style-declaration)))
