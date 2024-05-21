;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.content.to-dom
  (:require
   [app.common.text :as txt]
   [app.util.dom :as dom]
   [app.util.text.content.styles :as styles]))

(defn set-dataset
  [element data]
  (doseq [[data-name data-value] data]
    (dom/set-data! element (name data-name) data-value)))

(defn set-styles
  [element styles]
  (doseq [[style-name style-value] styles]
    (when (= style-name :vertical-align)
      (js/console.log "style-name" style-name style-value styles))
    (if (contains? styles/mapping style-name)
      (let [[style-encode] (get styles/mapping style-name)]
        (dom/set-style! element (name style-name) (style-encode style-value)))
      (dom/set-style! element (name style-name) style-value))))

(defn create-element
  ([tag]
   (create-element tag nil nil))
  ([tag attrs]
   (create-element tag attrs nil))
  ([tag attrs children]
   (let [element (dom/create-element tag)]
     ;; set attributes to the element if necessary.
     (when (some? attrs)
       (doseq [[attr-name attr-value] attrs]
         (cond
           (= attr-name :data)
           (set-dataset element attr-value)
           (= attr-name :style)
           (set-styles element attr-value)
           :else
           (dom/set-attribute! element (name attr-name) attr-value))))

     ;; add childs to the element if necessary.
     (when (some? children)
       (doseq [child children]
         (dom/append-child! element child)))

     ;; we need to return the DOM element
     element)))

(defn get-styles-from-attrs
  [node attrs]
  (reduce (fn [acc key] (assoc acc key (get node key))) {} attrs))

(defn get-paragraph-styles
  [paragraph]
  (get-styles-from-attrs paragraph txt/paragraph-attrs))

(defn get-root-styles
  [root]
  (get-styles-from-attrs root txt/root-attrs))

(defn get-inline-styles
  [inline]
  (get-styles-from-attrs inline txt/text-node-attrs))

(defn get-inline-children
  [inline]
  [(if (= "\n" (:text inline))
     (dom/create-element "br")
     (dom/create-text (:text inline)))])

(defn create-inline
  [inline]
  (create-element
   "span"
   {:id (:key inline)
    :data {:itype "inline"}
    :style (get-inline-styles inline)}
   (get-inline-children inline)))

(defn create-paragraph
  [paragraph]
  (create-element
   "div"
   {:id (:key paragraph)
    :data {:itype "paragraph"}
    :style (get-paragraph-styles paragraph)}
   (mapv create-inline (:children paragraph))))

(defn create-root
  [root]
  (let [root-styles (get-root-styles root)]
    #_(when (= (:vertical-align root-styles) "")
        (js-debugger))
    (create-element
     "div"
     {:id (:key root)
      :data {:itype "root"}
      :style root-styles}
     (mapv create-paragraph (get-in root [:children 0 :children])))))
