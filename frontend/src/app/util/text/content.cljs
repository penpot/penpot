;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.content
  (:require
   [app.common.text :as txt]
   [app.util.text.content.from-dom :as fd]
   [app.util.text.content.styles :as styles]
   [app.util.text.content.to-dom :as td]))

(defn get-style-defaults
  "Returns a Javascript object compatible with the TextEditor default styles"
  [style-defaults]
  (clj->js (reduce (fn [acc [k v]]
                     (if (contains? styles/mapping k)
                       (let [[style-encode] (get styles/mapping k)]
                         (assoc acc (styles/get-style-name k) (style-encode v)))
                       (assoc acc (name k) v))) {} style-defaults)))

(defn get-styles-from-event
  "Returns a ClojureScript object compatible with text nodes"
  [e]
  (let [style-declaration (.-detail e)]
    (reduce (fn [acc k]
              (if (contains? styles/mapping k)
                (let [style-name (styles/get-style-name k)
                      [_ style-decode] (get styles/mapping k)
                      style-value (.getPropertyValue style-declaration style-name)]
                  (assoc acc k (style-decode style-value)))
                (let [style-name (name k)
                      style-value (.getPropertyValue style-declaration style-name)]
                  (assoc acc k style-value)))) {} txt/text-style-attrs)))

(defn dom->cljs
  "Gets the editor content from a DOM structure"
  [root]
  (fd/create-root root))

(defn cljs->dom
  "Sets the editor content from a CLJS structure"
  [root]
  (td/create-root root))
