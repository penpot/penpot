(ns uxbox.ui.workspace.canvas
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.shapes :as shapes]
            [uxbox.library.icons :as _icons]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.util :as util]
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
  [own {:keys [id x y width height] :as shape}]
  (let [local (:rum/local own)
        selected (rum/react wb/selected-state)]
    (html
     [:g {
          :on-mouse-down
          (fn [event]
            (swap! local assoc :init-coords [x y])
            (rx/push! wb/selected-shape-b shape))

          :on-click
          (fn [ev]
            (when (= (:init-coords @local) [x y])
              (rs/emit! (dw/select-shape id))
              (println "click")))

          :on-mouse-up
          (fn [event]
            (println "mouse-up")
            (rx/push! wb/selected-shape-b :nothing)
            (dom/stop-propagation event))
          }
      (shapes/render shape)
      (if (contains? selected id)
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
                         {:cx (+ x width) :cy (+ y height)})]])])))


;; (defn- shape-render
;;   [own shape]
;;   (let [local (:rum/local own)
;;         x 30
;;         y 30
;;         width 100
;;         height 100]
;;     (html
;;      [:g
;;       (shapes/render shape {:x x :y y :width width :height height})
;;       [:g {:class "controls"}
;;        [:rect {:x x :y y :width width :height height
;;                :style {:stroke "black" :fill "transparent"
;;                        :stroke-opacity "0.5"}}]
;;        [:circle (merge default-selection-props
;;                        {:cx x :cy y})]
;;        [:circle (merge default-selection-props
;;                        {:cx (+ x width) :cy y})]
;;        [:circle (merge default-selection-props
;;                        {:cx x :cy (+ y height)})]
;;        [:circle (merge default-selection-props
;;                        {:cx (+ x width) :cy (+ y height)})]]])))

(def shape
  (util/component
   {:render shape-render
    :name "shape"
    :mixins [mx/static rum/reactive (mx/local {})]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canvas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn canvas-render
  []
  (let [page (rum/react wb/page-state)
        page-width (:width page)
        page-height (:height page)]
    (html
     [:svg.page-canvas
      {:x wb/document-start-x
       :y wb/document-start-y
       :ref "canvas"
       :width page-width
       :height page-height
       :on-mouse-up
       (fn [event]
         (rx/push! wb/selected-shape-b :nothing)
         (dom/stop-propagation event))
       }
      (background)
      [:svg.page-layout {}
       (for [shapeid (:shapes page)
             :let [item (get-in page [:shapes-by-id shapeid])]]
         (rum/with-key (shape item) (str shapeid)))]])))

(def canvas
  (util/component
   {:render canvas-render
    :name "canvas"
    :mixins [rum/reactive wb/mouse-mixin]}))

