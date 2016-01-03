(ns uxbox.ui.workspace.canvas
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [cats.labs.lens :as l]
            [goog.events :as events]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as shapes]
            [uxbox.util.lens :as ul]
            [uxbox.library.icons :as _icons]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.util :as util]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.grid :refer (grid)]
            [uxbox.ui.workspace.options :refer (element-opts)]
            [uxbox.ui.workspace.toolboxes :as toolboxes])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static shapes-by-id
  (as-> (l/key :shapes-by-id) $
    (l/focus-atom $ st/state)))

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
;; Select Rect
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn selrect-render
  [own]
  (when-let [data (rum/react wb/selrect-pos)]
    (let [{:keys [x y width height]} (wb/selrect->rect data)]
      (html
       [:rect.selection-rect
        {:x x
         :y y
         :width width
         :height height}]))))

(def ^:static selrect
  (util/component
   {:render selrect-render
    :name "selrect"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Selected shapes group
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

(defn- selected-shapes-render
  [own shapes selected]
  (let [local (:rum/local own)
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
;; Shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn shape-render
  [own shape selected]
  (let [{:keys [id x y width height] :as shape} shape
        selected? (contains? selected id)]
    (println "shape-render" id)
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
    :mixins [mx/static (mx/local {})]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canvas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- canvas-did-mount
  [own]
  (letfn [(on-mousemove [event page [offset-x offset-y]]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  event {:id (:id page)
                         :coords [(- x offset-x)
                                  (- y offset-y)]}]
              (rx/push! wb/mouse-b event)))]
    (let [[page] (:rum/props own)
          canvas (util/get-ref-dom own (str "canvas" (:id page)))
          brect (.getBoundingClientRect canvas)
          brect [(.-left brect) (.-top brect)]
          key (events/listen js/document EventType.MOUSEMOVE
                             #(on-mousemove % page brect))]
      (swap! wb/bounding-rect assoc (:id page) brect)
      (assoc own ::eventkey key))))

(defn- canvas-will-unmount
  [own]
  (let [key (::eventkey own)
        [page] (:rum/props own)]
    (swap! wb/bounding-rect dissoc (:id page))
    (events/unlistenByKey key)
    (dissoc own ::eventkey)))

(defn- canvas-transfer-state
  [old-own own]
  (let [key (::eventkey old-own)]
    (assoc own ::eventkey key)))

(defn- canvas-render
  [own {:keys [width height id] :as page}]
  (println "canvas-render" id)
  (let [workspace (rum/react wb/workspace-state)
        shapes-by-id (rum/react shapes-by-id)
        workspace-selected (:selected workspace)
        shapes (map #(get shapes-by-id %) (:shapes page))
        shapes-selected (filter (comp workspace-selected :id) shapes)
        shapes-notselected (filter (comp not workspace-selected :id) shapes)]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (when-not (empty? shapes-selected)
                (rs/emit! (dw/deselect-all)))
              (reset! wb/selrect-dragging? true))
            (on-mouse-up [event]
              (dom/stop-propagation event)
              (reset! wb/shapes-dragging? false)
              (reset! wb/selrect-dragging? false))]
      (html
       [:svg#page-canvas.page-canvas {:x wb/document-start-x
                                      :y wb/document-start-y
                                      :ref (str "canvas" id)
                                      :width width
                                      :height height
                                      :on-mouse-down on-mouse-down
                                      :on-mouse-up on-mouse-up}
        (background)
        (grid 1)
        [:svg.page-layout {}
         (for [item shapes-notselected
               :let [component (shape item workspace-selected)]]
           (rum/with-key component (str (:id item))))

         (cond
           (= (count shapes-selected) 1)
           (let [item (first shapes-selected)]
             (shape item workspace-selected))

           (> (count shapes-selected) 1)
           (selected-shapes shapes-selected))
         (selrect)]]))))

(def canvas
  (util/component
   {:render canvas-render
    :did-mount canvas-did-mount
    :will-unmount canvas-will-unmount
    :transfer-state canvas-transfer-state
    :name "canvas"
    :mixins [mx/static rum/reactive]}))
