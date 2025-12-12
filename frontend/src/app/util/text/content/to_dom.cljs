;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.content.to-dom
  (:require
   [app.common.data :as d]
   [app.common.types.text :as txt]
   [app.util.dom :as dom]
   [app.util.text.content.styles :as styles]))

(defn set-dataset
  [element data]
  (doseq [[data-name data-value] data]
    (dom/set-data! element (name data-name) data-value)))

(defn set-styles
  [element styles]
  (doseq [[style-name style-value] styles]
    (if (contains? styles/mapping style-name)
      (let [[style-encode] (get styles/mapping style-name)
            style-encoded-value (style-encode style-value)]
        (dom/set-style! element (styles/get-style-name-as-css-variable style-name) style-encoded-value))
      (dom/set-style! element (styles/get-style-name style-name) (styles/normalize-style-value style-name style-value)))))

(defn create-element
  ([tag]
   (create-element tag nil nil))
  ([tag attrs]
   (create-element tag attrs nil))
  ([tag attrs children]
   (let [element (dom/create-element tag)]
     ;; set attributes to the element if necessary.
     (doseq [[attr-name attr-value] attrs]
       (case attr-name
         :data (set-dataset element attr-value)
         :style (set-styles element attr-value)
         (dom/set-attribute! element (name attr-name) attr-value)))

     ;; add childs to the element if necessary.
     (doseq [child children]
       (dom/append-child! element child))

     ;; we need to return the DOM element
     element)))

(defn get-styles-from-attrs
  [node attrs defaults]
  (let [styles (reduce
                (fn [acc key]
                  (let [default-value (get defaults key)]
                    (assoc acc key (get node key default-value)))) {} attrs)
        fills
        (cond
           ;; DEPRECATED: still here for backward compatibility with
           ;; old penpot files that still has a single color.
          (or (some? (:fill-color node))
              (some? (:fill-opacity node))
              (some? (:fill-color-gradient node)))
          [(d/without-nils (select-keys node [:fill-color :fill-opacity :fill-color-gradient
                                              :fill-color-ref-id :fill-color-ref-file]))]

          (nil? (:fills node))
          [{:fill-color "#000000" :fill-opacity 1}]

          :else
          (:fills node))]
    (assoc styles :fills fills)))

(defn get-paragraph-styles
  [paragraph]
  (let [styles (get-styles-from-attrs
                paragraph
                (d/concat-set txt/paragraph-attrs txt/text-node-attrs)
                txt/default-text-attrs)
        ;; If the text is not empty we must the paragraph font size to 0,
        ;; it affects to the height calculation the browser does
        font-size (if (some #(not= "" (:text %)) (:children paragraph))
                    "0"
                    (:font-size styles (:font-size txt/default-typography)))

        line-height (:line-height styles)
        line-height (if (and (some? line-height) (not= "" line-height))
                      line-height
                      (:line-height txt/default-typography))]
    (-> styles
        (assoc :font-size font-size :line-height line-height))))

(defn get-root-styles
  [root]
  (get-styles-from-attrs root txt/root-attrs txt/default-text-attrs))

(defn get-inline-styles
  [inline paragraph]
  (let [node (if (= "" (:text inline)) paragraph inline)
        styles (get-styles-from-attrs node txt/text-node-attrs txt/default-text-attrs)]
    (dissoc styles :line-height)))

(defn normalize-spaces
  "Add zero-width spaces after forward slashes to enable word breaking"
  [text]
  (when text
    (.replace text (js/RegExp "/" "g") "/\u200B")))

(defn get-inline-children
  [inline paragraph]
  [(if (and (= "" (:text inline))
            (= 1 (count (:children paragraph))))
     (dom/create-element "br")
     (dom/create-text (normalize-spaces (:text inline))))])

(defn create-random-key
  []
  (.toString (.floor js/Math (* (.random js/Math) (.-MAX_SAFE_INTEGER js/Number))) 36))

(defn has-content?
  [paragraph]
  (some #(not= "" (:text % "")) (:children paragraph)))

(defn create-inline
  [inline paragraph]
  (create-element
   "span"
   {:id (or (:key inline) (create-random-key))
    :data {:itype "inline"}
    :style (get-inline-styles inline paragraph)}
   (get-inline-children inline paragraph)))

(defn create-paragraph
  [paragraph]
  (create-element
   "div"
   {:id (or (:key paragraph) (create-random-key))
    :data {:itype "paragraph"}
    :style (get-paragraph-styles paragraph)}
   (mapv #(create-inline % paragraph) (:children paragraph))))

(defn create-root
  [root]
  (let [root-styles (get-root-styles root)
        paragraphs (get-in root [:children 0 :children])
        filtered-paragraphs (->> paragraphs
                                 (map-indexed vector)
                                 (mapv second))]
    (create-element
     "div"
     {:id (or (:key root) (create-random-key))
      :data {:itype "root"}
      :style root-styles}
     (mapv create-paragraph filtered-paragraphs))))
