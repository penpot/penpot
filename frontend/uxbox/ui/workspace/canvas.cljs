(ns uxbox.ui.workspace.canvas
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.shapes :as shapes]
            [uxbox.library.icons :as _icons]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]
            [uxbox.data.projects :as dp]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.rules :as wr]
            [uxbox.ui.workspace.toolboxes :as toolboxes]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Background
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn background-render
  []
  (html
   [:rect
    {:x 0 :y 0 :width "100%" :height "100%" :fill "white"}]))

(def background
  (util/component
   {:render background-render
    :name "background"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private
  selection-circle-style
  {:fillOpacity "0.5"
   :strokeWidth "1px"
   :vectorEffect "non-scaling-stroke"
   :cursor "move"})

(def ^:private
  default-selection-props
  {:r 4 :style selection-circle-style
   :fill "lavender"
   :stroke "gray"})

(defn- shape-render
  [own shape]
  (let [local (:rum/local own)
        x 30
        y 30
        width 100
        height 100]
    (html
     [:g
      (shapes/render shape {:x x :y y :width width :height height})
      [:g {:class "controls"}
       [:rect {:x x :y y :width width :height height
               :style {:stroke "black" :fill "transparent"
                       :stroke-opacity "0.5"}}]
       [:circle (merge default-selection-props
                       {:cx x :cy y})]
       [:circle (merge default-selection-props
                       {:cx (+ x width) :cy y})]
       [:circle (merge default-selection-props
                       {:cx x :cy (+ y height)})]
       [:circle (merge default-selection-props
                       {:cx (+ x width) :cy (+ y height)})]]])))

(def shape
  (util/component
   {:render shape-render
    :name "shape"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canvas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn canvas-render
  []
  (let [page (rum/react wb/page-state)
        page-width (:width page)
        page-height (:height page)
        ;; selection-uuids (rum/react selected-ids)
        ;; selected-shapes (rum/react selected-shapes)
        ;; raw-shapes (into []
        ;;                  (comp
        ;;                   (filter :shape/visible?)
        ;;                   (filter #(not (contains? selection-uuids (:shape/uuid %))))
        ;;                   (map :shape/data))
        ;;                  shapes)
        item (first _icons/+external+)
        ]
    (html
     [:svg#page-canvas
      {:x wb/document-start-x
       :y wb/document-start-y
       :ref "canvas"
       :width page-width
       :height page-height
       ;; :on-mouse-down cs/on-mouse-down
       ;; :on-mouse-up cs/on-mouse-up
       }
      (background)
      [:svg#page-layout
       (shape item)]
      #_(apply vector :svg#page-layout (map shapes/shape->svg raw-shapes))
      #_(when-let [shape (rum/react drawing)]
          (shapes/shape->drawing-svg shape))
      #_(when-not (empty? selected-shapes)
          (let [rs selected-shapes]
            (vec (cons :g
                       (concat
                        (map shapes/shape->selected-svg rs)
                        (map shapes/shape->svg rs))))))])))

(def canvas
  (util/component
   {:render canvas-render
    :name "canvas"
    :mixins [rum/reactive wb/mouse-mixin]}))

