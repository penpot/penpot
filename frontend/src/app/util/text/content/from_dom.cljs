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
    "\n"
    (.-textContent element)))

(defn get-styles-from-attrs
  [element attrs]
  (reduce (fn [acc key]
            (if (contains? styles/mapping key)
              (let [[style-name _ style-decode] (get styles/mapping key)
                    value (style-decode (.getPropertyValue (.-style element) style-name))]
                (assoc acc key value))
              (assoc acc key (.getPropertyValue (.-style element) (name key))))) {} attrs))

(defn get-inline-styles
  [element]
  (get-styles-from-attrs element txt/text-node-attrs))

(defn get-paragraph-styles
  [element]
  (get-styles-from-attrs element txt/paragraph-attrs))

(defn get-root-styles
  [element]
  (get-styles-from-attrs element txt/root-attrs))

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
  (d/merge {:type "root",
            :key (.-id element)
            :children [{:type "paragraph-set"
                        :children (mapv create-paragraph (.-children element))}]}
           (get-root-styles element)))
