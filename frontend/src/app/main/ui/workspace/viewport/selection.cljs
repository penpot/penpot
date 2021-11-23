;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.selection
  "Selection handlers component."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.cursors :as cur]
   [app.main.ui.workspace.shapes.path.editor :refer [path-editor]]
   [app.util.debug :refer [debug?]]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [rumext.util :refer [map->obj]]))

(def rotation-handler-size 20)
(def resize-point-radius 4)
(def resize-point-circle-radius 10)
(def resize-point-rect-size 8)
(def resize-side-height 8)
(def selection-rect-color-normal "#1FDEA7")
(def selection-rect-color-component "#00E0FF")
(def selection-rect-width 1)
(def min-selrect-side 10)
(def small-selrect-side 30)

(mf/defc selection-rect [{:keys [transform rect zoom color on-move-selected]}]
  (when rect
    (let [{:keys [x y width height]} rect]
      [:rect.main.viewport-selrect
       {:x x
        :y y
        :width width
        :height height
        :transform transform
        :on-mouse-down on-move-selected
        :style {:stroke color
                :stroke-width (/ selection-rect-width zoom)
                :fill "none"}}])))

(defn- handlers-for-selection [{:keys [x y width height]} {:keys [type]} zoom]
  (let [zoom-width (* width zoom)
        zoom-height (* height zoom)

        align (when (or (<= zoom-width small-selrect-side)
                        (<= zoom-height small-selrect-side))
                :outside)
        show-resize-point? (or (not= type :path)
                               (and
                                (> zoom-width min-selrect-side)
                                (> zoom-height min-selrect-side)))
        min-side-top? (or (not= type :path) (> zoom-height min-selrect-side))
        min-side-side? (or (not= type :path) (> zoom-width min-selrect-side))]
    (->>
     [ ;; TOP-LEFT
      {:type :rotation
       :position :top-left
       :props {:cx x :cy y}}

      (when show-resize-point?
        {:type :resize-point
         :position :top-left
         :props {:cx x :cy y :align align}})

      {:type :rotation
       :position :top-right
       :props {:cx (+ x width) :cy y}}

      (when show-resize-point?
        {:type :resize-point
         :position :top-right
         :props {:cx (+ x width) :cy y :align align}})

      {:type :rotation
       :position :bottom-right
       :props {:cx (+ x width) :cy (+ y height)}}

      (when show-resize-point?
        {:type :resize-point
         :position :bottom-right
         :props {:cx (+ x width) :cy (+ y height) :align align}})

      {:type :rotation
       :position :bottom-left
       :props {:cx x :cy (+ y height)}}

      (when show-resize-point?
        {:type :resize-point
         :position :bottom-left
         :props {:cx x :cy (+ y height) :align align}})

      (when min-side-top?
        {:type :resize-side
         :position :top
         :props {:x x :y y :length width :angle 0 :align align}})

      (when min-side-side?
        {:type :resize-side
         :position :right
         :props {:x (+ x width) :y y :length height :angle 90 :align align}})

      (when min-side-top?
        {:type :resize-side
         :position :bottom
         :props {:x (+ x width) :y (+ y height) :length width :angle 180 :align align}})

      (when min-side-side?
        {:type :resize-side
         :position :left
         :props {:x x :y (+ y height) :length height :angle 270 :align align}})]

     (filterv (comp not nil?)))))

(mf/defc rotation-handler [{:keys [cx cy transform position rotation zoom on-rotate]}]
  (let [size (/ rotation-handler-size zoom)
        x (- cx (if (#{:top-left :bottom-left} position) size 0))
        y (- cy (if (#{:top-left :top-right} position) size 0))
        angle (case position
                :top-left 0
                :top-right 90
                :bottom-right 180
                :bottom-left 270)]
    [:rect {:style {:cursor (cur/rotate (+ rotation angle))}
            :x x
            :y y
            :width size
            :height size
            :fill (if (debug? :rotation-handler) "blue" "none")
            :transform transform
            :on-mouse-down on-rotate}]))

(mf/defc resize-point-handler
  [{:keys [cx cy zoom position on-resize transform rotation color overflow-text align]}]
  (let [cursor (if (#{:top-left :bottom-right} position)
                 (cur/resize-nesw rotation) (cur/resize-nwse rotation))
        {cx' :x cy' :y} (gpt/transform (gpt/point cx cy) transform)]

    [:g.resize-handler
     [:circle {:r (/ resize-point-radius zoom)
               :style {:fillOpacity "1"
                       :strokeWidth "1px"
                       :vectorEffect "non-scaling-stroke"}
               :fill "#FFFFFF"
               :stroke (if (and (= position :bottom-right) overflow-text) "red" color)
               :cx cx'
               :cy cy'}]

     (if (= align :outside)
       (let [resize-point-circle-radius (/ resize-point-circle-radius zoom)
             offset-x (if (#{:top-right :bottom-right} position) 0 (- resize-point-circle-radius))
             offset-y (if (#{:bottom-left :bottom-right} position) 0 (- resize-point-circle-radius))
             cx (+ cx offset-x)
             cy (+ cy offset-y)
             {cx' :x cy' :y} (gpt/transform (gpt/point cx cy) transform)]
         [:rect {:x cx'
                 :y cy'
                 :width resize-point-circle-radius
                 :height resize-point-circle-radius
                 :transform (when rotation (str/fmt "rotate(%s, %s, %s)" rotation cx' cy'))
                 :style {:fill (if (debug? :resize-handler) "red" "none")
                         :cursor cursor}
                 :on-mouse-down #(on-resize {:x cx' :y cy'} %)}])

       [:circle {:on-mouse-down #(on-resize {:x cx' :y cy'} %)
                 :r (/ resize-point-circle-radius zoom)
                 :cx cx'
                 :cy cy'
                 :style {:fill (if (debug? :resize-handler) "red" "none")
                         :cursor cursor}}]
       )]))

(mf/defc resize-side-handler
  "The side handler is always rendered horizontally and then rotated"
  [{:keys [x y length align angle zoom position rotation transform on-resize]}]
  (let [res-point (if (#{:top :bottom} position)
                    {:y y}
                    {:x x})
        target-length (max 0 (- length (/ (* resize-point-rect-size 2) zoom)))

        width (if (< target-length 6) length target-length)
        height (/ resize-side-height zoom)

        offset-x (/ (- length width) 2)
        offset-y (if (= align :outside) (- height) (- (/ height 2)))

        target-x (+ x offset-x)
        target-y (+ y offset-y)]
    [:rect {:x target-x
            :y target-y
            :width width
            :height height
            :transform (gmt/multiply transform
                                     (gmt/rotate-matrix angle (gpt/point x y)))
            :on-mouse-down #(on-resize res-point %)
            :style {:fill (if (debug? :resize-handler) "yellow" "none")
                    :cursor (if (#{:left :right} position)
                              (cur/resize-ew rotation)
                              (cur/resize-ns rotation)) }}]))

(defn minimum-selrect [{:keys [x y width height] :as selrect}]
  (let [final-width (max width min-selrect-side)
        final-height (max height min-selrect-side)
        offset-x (/ (- final-width width) 2)
        offset-y (/ (- final-height height) 2)]
    {:x (- x offset-x)
     :y (- y offset-y)
     :width final-width
     :height final-height}))

(mf/defc controls
  {::mf/wrap-props false}
  [props]
  (let [{:keys [overflow-text] :as shape} (obj/get props "shape")
        zoom              (obj/get props "zoom")
        color             (obj/get props "color")
        on-move-selected  (obj/get props "on-move-selected")
        on-resize         (obj/get props "on-resize")
        on-rotate         (obj/get props "on-rotate")
        disable-handlers  (obj/get props "disable-handlers")
        current-transform (mf/deref refs/current-transform)

        selrect (:selrect shape)
        transform (geom/transform-matrix shape {:no-flip true})

        rotation (-> (gpt/point 1 0)
                     (gpt/transform (:transform shape))
                     (gpt/angle)
                     (mod 360))]

    (when (not (#{:move :rotate} current-transform))
      [:g.controls {:pointer-events (if disable-handlers "none" "visible")}

       ;; Selection rect
       [:& selection-rect {:rect selrect
                           :transform transform
                           :zoom zoom
                           :color color
                           :on-move-selected on-move-selected}]

       ;; Handlers
       (for [{:keys [type position props]} (handlers-for-selection selrect shape zoom)]
         (let [common-props {:key (str (name type) "-" (name position))
                             :zoom zoom
                             :position position
                             :on-rotate on-rotate
                             :on-resize (partial on-resize position)
                             :transform transform
                             :rotation rotation
                             :color color
                             :overflow-text overflow-text}
               props (map->obj (merge common-props props))]
           (case type
             :rotation (when (not= :frame (:type shape)) [:> rotation-handler props])
             :resize-point [:> resize-point-handler props]
             :resize-side [:> resize-side-handler props])))])))

;; --- Selection Handlers (Component)
;; TODO: add specs for clarity

(mf/defc text-edition-selection-handlers
  [{:keys [shape color] :as props}]
  (let [{:keys [x y width height]} shape]
    [:g.controls
     [:rect.main {:x x :y y
                  :transform (geom/transform-matrix shape)
                  :width width
                  :height height
                  :pointer-events "visible"
                  :style {:stroke color
                          :stroke-width "0.5"
                          :stroke-opacity "1"
                          :fill "none"}}]]))

(mf/defc multiple-selection-handlers
  [{:keys [shapes selected zoom color disable-handlers on-move-selected] :as props}]
  (let [shape (mf/use-memo
               (mf/deps shapes)
               #(->> shapes
                     (map geom/transform-shape)
                     (geom/selection-rect)
                     (geom/setup {:type :rect})))

        shape-center (geom/center-shape shape)

        on-resize
        (fn [current-position _initial-position event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (st/emit! (dw/start-resize current-position selected shape))))

        on-rotate
        (fn [event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (st/emit! (dw/start-rotate shapes))))]

    [:*
     [:& controls {:shape shape
                   :zoom zoom
                   :color color
                   :disable-handlers disable-handlers
                   :on-move-selected on-move-selected
                   :on-resize on-resize
                   :on-rotate on-rotate}]

     (when (debug? :selection-center)
       [:circle {:cx (:x shape-center) :cy (:y shape-center) :r 5 :fill "yellow"}])]))

(mf/defc single-selection-handlers
  [{:keys [shape zoom color disable-handlers on-move-selected] :as props}]
  (let [shape-id (:id shape)
        shape (geom/transform-shape shape {:round-coords? false})

        shape' (if (debug? :simple-selection) (geom/setup {:type :rect} (geom/selection-rect [shape])) shape)

        on-resize
        (fn [current-position _initial-position event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (st/emit! (dw/start-resize current-position #{shape-id} shape'))))

        on-rotate
        (fn [event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (st/emit! (dw/start-rotate [shape]))))]

    [:& controls {:shape shape'
                  :zoom zoom
                  :color color
                  :on-rotate on-rotate
                  :on-resize on-resize
                  :disable-handlers disable-handlers
                  :on-move-selected on-move-selected}]))

(mf/defc selection-handlers
  {::mf/wrap [mf/memo]}
  [{:keys [shapes selected edition zoom disable-handlers on-move-selected] :as props}]
  (let [num (count shapes)
        {:keys [type] :as shape} (first shapes)

        color (if (or (> num 1) (nil? (:shape-ref shape)))
                selection-rect-color-normal
                selection-rect-color-component)]
    (cond
      (zero? num)
      nil

      (> num 1)
      [:& multiple-selection-handlers {:shapes shapes
                                       :selected selected
                                       :zoom zoom
                                       :color color
                                       :disable-handlers disable-handlers
                                       :on-move-selected on-move-selected}]

      (and (= type :text)
           (= edition (:id shape)))
      [:& text-edition-selection-handlers {:shape shape
                                           :zoom zoom
                                           :color color}]

      (= edition (:id shape))
      [:& path-editor {:zoom zoom
                       :shape shape}]

      :else
      [:& single-selection-handlers {:shape shape
                                     :zoom zoom
                                     :color color
                                     :disable-handlers disable-handlers
                                     :on-move-selected on-move-selected}])))
