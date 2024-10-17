;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.content.from-dom
  (:require
   [app.common.data :as d]
   [app.common.text :as txt]
   [app.util.text.content.styles :as styles]))

(defn is-text-node
  [node]
  (= (.-nodeType node) js/Node.TEXT_NODE))

(defn is-element
  [node tag]
  (and (= (.-nodeType node) js/Node.ELEMENT_NODE)
       (= (.-nodeName node) (.toUpperCase tag))))

(defn is-line-break
  [node]
  (is-element node "br"))

(defn is-inline-child
  [node]
  (or (is-line-break node)
      (is-text-node node)))

(defn get-inline-text
  [element]
  (when-not (is-inline-child (.-firstChild element))
    (throw (js/TypeError. "Invalid inline child")))
  (if (is-line-break (.-firstChild element))
    ""
    (.-textContent element)))

(defn get-attrs-from-styles
  [element attrs]
  (reduce (fn [acc key]
            (let [style (.-style element)]
              (if (contains? styles/mapping key)
                (let [style-name (styles/get-style-name-as-css-variable key)
                      [_ style-decode] (get styles/mapping key)
                      value (style-decode (.getPropertyValue style style-name))]
                  (assoc acc key value))
                (let [style-name (styles/get-style-name key)]
                  (assoc acc key (styles/normalize-attr-value key (.getPropertyValue style style-name))))))) {} attrs))

(defn get-inline-styles
  [element]
  (get-attrs-from-styles element txt/text-node-attrs))

(defn get-paragraph-styles
  [element]
  (get-attrs-from-styles element (d/concat-set txt/paragraph-attrs txt/text-node-attrs)))

(defn get-root-styles
  [element]
  (get-attrs-from-styles element txt/root-attrs))

(defn create-inline
  [element]
  (d/merge {:text (get-inline-text element)
            :key (.-id element)}
           (get-inline-styles element)))

(defn create-paragraph
  [element]
  (d/merge {:type "paragraph"
            :key (.-id element)
            :children (mapv create-inline (.-children element))}
           (get-paragraph-styles element)))

(defn create-root
  [element]
  (let [root-styles (get-root-styles element)]
    (d/merge {:type "root",
              :key (.-id element)
              :children [{:type "paragraph-set"
                          :children (mapv create-paragraph (.-children element))}]}
             root-styles)))
