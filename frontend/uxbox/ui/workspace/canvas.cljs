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
            [uxbox.ui.workspace.grid :refer (grid)]
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
   :vectorEffect "non-scaling-stroke"})

(def ^:private
  default-selection-props
  {:r 4 :style selection-circle-style
   :fill "lavender"
   :stroke "gray"})

(defn shape-render
  [own {:keys [id x y width height] :as shape}]
  (let [selected (rum/react wb/selected-state)
        selected? (contains? selected id)]
    (letfn [(on-mouse-down [event]
              (let [local (:rum/local own)]
                (dom/stop-propagation event)
                (swap! local assoc :init-coords [x y])
                (reset! wb/shapes-dragging? true))
              (cond
                (and (not selected?)
                     (empty? selected))
                (rs/emit! (dw/select-shape id))

                (and (not selected?)
                     (not (empty? selected)))
                (if (.-ctrlKey event)
                  (rs/emit! (dw/select-shape id))
                  (rs/emit! (dw/deselect-all)
                            (dw/select-shape id)))))
            (on-mouse-up [event]
              (dom/stop-propagation event)
              (reset! wb/shapes-dragging? false))]
      (html
       [:g.shape {:class (when selected? "selected")
                  :on-mouse-down on-mouse-down
                  :on-mouse-up on-mouse-up}
        (shapes/render shape)
        (if selected?
          [:g.controls
           [:rect {:x x :y y :width width :height height
                   :style {:stroke "black" :fill "transparent"
                         :stroke-opacity "0.5"}}]
           [:circle.top-left (merge default-selection-props
                                    {:cx x :cy y})]
           [:circle.top-right (merge default-selection-props
                                     {:cx (+ x width) :cy y})]
           [:circle.bottom-left (merge default-selection-props
                                       {:cx x :cy (+ y height)})]
           [:circle.bottom-right (merge default-selection-props
                                        {:cx (+ x width) :cy (+ y height)})]])]))))

(def shape
  (util/component
   {:render shape-render
    :name "shape"
    :mixins [mx/static rum/reactive (mx/local {})]}))

(defn- selected-shapes-render
  [own shapes]
  (let [selected (rum/react wb/selected-state)
        local (:rum/local own)
        x (apply min (map :x shapes))
        y (apply min (map :y shapes))
        x' (apply max (map (fn [{:keys [x width]}] (+ x width)) shapes))
        y' (apply max (map (fn [{:keys [y height]}] (+ y height)) shapes))
        width (- x' x)
        height (- y' y)]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (swap! local assoc :init-coords [x y])
              (reset! wb/shapes-dragging? true))
            (on-mouse-up [event]
              (dom/stop-propagation event)
              (reset! wb/shapes-dragging? false))]
      (html
       [:g {:class "shape selected"
            :on-mouse-down on-mouse-down
            :on-mouse-up on-mouse-up}
        (for [item shapes]
          (shapes/render item))
        [:g.controls
         [:rect {:x x :y y :width width :height height
                 :style {:stroke "black" :fill "transparent"
                         :stroke-opacity "0.5"}}]
         [:circle.top-left (merge default-selection-props
                                  {:cx x :cy y})]
         [:circle.top-right (merge default-selection-props
                                   {:cx (+ x width) :cy y})]
         [:circle.bottom-left (merge default-selection-props
                                     {:cx x :cy (+ y height)})]
         [:circle.bottom-right (merge default-selection-props
                                      {:cx (+ x width) :cy (+ y height)})]]]))))

(def selected-shapes
  (util/component
   {:render selected-shapes-render
    :name "selected-shapes"
    :mixins [mx/static rum/reactive (mx/local {})]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canvas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn canvas-render
  []
  (let [page (rum/react wb/page-state)
        shapes (rum/react wb/shapes-state)
        selected-ids (rum/react wb/selected-state)
        selected (filter (comp selected-ids :id) shapes)
        nonselected (filter (comp not selected-ids :id) shapes)
        page-width (:width page)
        page-height (:height page)]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (rs/emit! (dw/deselect-all)))
            (on-mouse-up [event]
              (dom/stop-propagation event)
              (reset! wb/shapes-dragging? false))]
      (html
       [:svg.page-canvas {:x wb/document-start-x
                          :y wb/document-start-y
                          :ref "canvas"
                          :width page-width
                          :height page-height
                          :on-mouse-down on-mouse-down
                          :on-mouse-up on-mouse-up}
        (background)
        (grid 1)
        [:svg.page-layout {}
         (for [item nonselected]
           (rum/with-key (shape item) (str (:id item))))


         (cond
           (= (count selected) 1)
           (shape (first selected))

           (> (count selected) 1)
           (selected-shapes selected))]]))))

(def canvas
  (util/component
   {:render canvas-render
    :name "canvas"
    :mixins [rum/reactive wb/mouse-mixin]}))

