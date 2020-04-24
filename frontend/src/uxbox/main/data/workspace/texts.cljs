;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace.texts
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [goog.events :as events]
   [goog.object :as gobj]
   [potok.core :as ptk]
   [uxbox.main.fonts :as fonts]
   ["slate" :as slate :refer [Editor Transforms Text]]))

(defn assign-editor
  [editor]
  (ptk/reify ::assign-editor
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :editor] editor)
          (update-in [:workspace-local :editor-n] (fnil inc 0))))))

;; --- Helpers

(defn set-nodes!
  ([editor props]
   (set-nodes! editor props #js {}))
  ([editor props options]
   (.setNodes Transforms editor props options)
   editor))

(defn is-text?
  [v]
  (.isText Text v))

(defn is-paragraph?
  [v]
  (= (.-type v) "paragraph"))

;; --- Predicates

(defn enabled?
  [editor universal? pred]
  (when editor
    (let [result (.nodes Editor editor #js {:match pred :universal universal?})
          match  (first (es6-iterator-seq result))]
      (array? match))))

(defn text-decoration-enabled?
  [editor type]
  (enabled? editor true
            (fn [v]
              (let [val (unchecked-get v "textDecoration")]
                (identical? type val)))))

(defn text-transform-enabled?
  [editor type]
  (enabled? editor true
            (fn [v]
              (let [val (unchecked-get v "textTransform")]
                (identical? type val)))))

(defn text-align-enabled?
  [editor type]
  (enabled? editor false
            (fn [v]
              (let [val (unchecked-get v "textAlign")]
                (identical? type val)))))

(defn vertical-align-enabled?
  [editor type]
  (enabled? editor false
            (fn [v]
              (let [val (unchecked-get v "verticalAlign")]
                (identical? type val)))))

;; --- Getters

(defn current-value
  [editor {:keys [universal?
                  attr
                  pred
                  at]
           :as opts}]
  (when editor
    (let [options #js {:match pred :universal universal?}
          default-loc #js {:path #js [0 0] :offset 0}]

      (cond
        (object? at)
        (unchecked-set options "at" at)

        (nil? (unchecked-get editor "selection"))
        (unchecked-set options "at" default-loc))

      (let [result (.nodes Editor editor options)
            match  (ffirst (es6-iterator-seq result))]
        (when (object? match)
          (unchecked-get match attr))))))

(defn current-line-height
  [editor {:keys [at default]}]
  (or (current-value editor {:at at
                             :pred is-paragraph?
                             :attr "lineHeight"
                             :universal? false})
      default))

(defn current-letter-spacing
  [editor {:keys [at default]}]
  (or (current-value editor {:at at
                             :pred is-text?
                             :attr "letterSpacing"
                             :universal? true})
      default))


(defn current-font-family
  [editor {:keys [at default]}]
  (or (current-value editor {:at at
                             :pred is-text?
                             :attr "fontId"
                             :universal? true})
      default))

(defn current-font-size
  [editor {:keys [at default]}]
  (or (current-value editor {:at at
                             :pred is-text?
                             :attr "fontSize"
                             :universal? true})
      default))


(defn current-font-variant
  [editor {:keys [at default]}]
  (or (current-value editor {:at at
                             :pred is-text?
                             :attr "fontVariantId"
                             :universal? true})
      default))


(defn current-fill
  [editor {:keys [at default]}]
  (or (current-value editor {:at at
                             :pred is-text?
                             :attr "fill"
                             :universal? true})
      default))


(defn current-opacity
  [editor {:keys [at default]}]
  (or (current-value editor {:at at
                             :pred is-text?
                             :attr "opacity"
                             :universal? true})
      default))


;; --- Setters


(defn set-text-decoration!
  [editor type]
  (set-nodes! editor
              #js {:textDecoration type}
              #js {:match is-text?
                   :split true}))

(defn set-text-align!
  [editor type]
  (set-nodes! editor
              #js {:textAlign type}
              #js {:match is-paragraph?}))

(defn set-text-transform!
  [editor type]
  (set-nodes! editor
              #js {:textTransform type}
              #js {:match is-text?
                   :split true}))

(defn set-vertical-align!
  [editor type]
  (set-nodes! editor
              #js {:verticalAlign type}
              #js {:match (fn [item]
                            (= "text-box" (unchecked-get item "type")))}))

(defn set-line-height!
  [editor val at]
  (set-nodes! editor
              #js {:lineHeight val}
              #js {:at at
                   :match is-paragraph?}))

(defn set-letter-spacing!
  [editor val at]
  (set-nodes! editor
              #js {:letterSpacing val}
              #js {:at at
                   :match is-text?
                   :split true}))

(defn set-font!
  [editor id family]
  (set-nodes! editor
              #js {:fontId id
                   :fontFamily family}
              #js {:match is-text?
                   :split true}))

(defn set-font-size!
  [editor val]
  (set-nodes! editor
              #js {:fontSize val}
              #js {:match is-text?
                   :split true}))

(defn set-font-variant!
  [editor id weight style]
  (set-nodes! editor
              #js {:fontVariantId id
                   :fontWeight weight
                   :fontStyle style}
              #js {:match is-text?
                   :split true}))

(defn set-fill!
  [editor val]
  (set-nodes! editor
              #js {:fill val}
              #js {:match is-text?
                   :split true}))

(defn set-opacity!
  [editor val]
  (set-nodes! editor
              #js {:opacity val}
              #js {:match is-text?
                   :split true}))
