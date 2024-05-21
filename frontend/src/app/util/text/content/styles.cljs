;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.content.styles
  (:require
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

(defn get-style-name
  [key]
  (str/concat "--" (name key)))

(defn get-style-keyword
  [key]
  (keyword (str/concat "--" (name key))))

(defn get-attr-keyword
  [style-name]
  (keyword (str/slice style-name 2)))

(defn attr-needs-mapping?
  [key]
  (let [contained? (contains? mapping key)]
    contained?))

(defn attr->style-key
  [key]
  (if (attr-needs-mapping? key)
    (let [name (get-style-name key)]
      (keyword name))
    key))

(defn attr->style-value
  [key value]
  (if (attr-needs-mapping? key)
    (let [[encoder] (get mapping key)]
      (encoder value))
    value))

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
  [name value]
  (if (style-needs-mapping? name)
    (let [key (get-attr-keyword name)
          [_ decoder] (get mapping key)]
      (decoder value))
    value))

(defn style->attr
  [[key value]]
  [(style->attr-key key)
   (style->attr-value key value)])

(defn styles->attrs
  "Maps styles to attrs"
  [styles]
  (let [mapped-attrs
        (into {} (map style->attr styles))]
    mapped-attrs))
