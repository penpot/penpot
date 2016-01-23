(ns uxbox.ui.workspace.selrect
  "Components for indicate the user selection and selected shapes group."
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mouse Selection Rect
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mouse-selrect-render
  [own]
  (when-let [data (rum/react wb/selrect-pos)]
    (let [{:keys [x y width height]} (wb/selrect->rect data)]
      (html
       [:rect.selection-rect
        {:x x
         :y y
         :width width
         :height height}]))))

(def ^:static mouse-selrect
  (mx/component
   {:render mouse-selrect-render
    :name "mouse-selrect"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes Selection Rect
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private selection-circle-style
  {:fillOpacity "0.5"
   :strokeWidth "1px"
   :vectorEffect "non-scaling-stroke"})

(def ^:private default-selection-props
  {:r 5 :style selection-circle-style
   :fill "#333"
   :stroke "#333"})

(defn shapes-selrect-render
  [own shapes]
  (when (seq shapes)
    (let [{:keys [width height x y]} (sh/outer-rect shapes)]
      (html
       [:g.controls
        [:rect {:x x :y y :width width :height height :stroke-dasharray "5,5"
                :style {:stroke "#333" :fill "transparent"
                        :stroke-opacity "1"}}]
        [:circle.top-left (merge default-selection-props
                                 {:cx x :cy y})]
        [:circle.top-right (merge default-selection-props
                                  {:cx (+ x width) :cy y})]
        [:circle.bottom-left (merge default-selection-props
                                    {:cx x :cy (+ y height)})]
        [:circle.bottom-right (merge default-selection-props
                                     {:cx (+ x width) :cy (+ y height)})]]))))

(def ^:static shapes-selrect
  (mx/component
   {:render shapes-selrect-render
    :name "shapes-selrect"
    :mixins [mx/static]}))
