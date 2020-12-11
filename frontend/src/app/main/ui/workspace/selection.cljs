;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.selection
  "Selection handlers component."
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [rumext.util :refer [map->obj]]
   [app.common.uuid :as uuid]
   [app.util.data :as d]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.cursors :as cur]
   [app.common.math :as mth]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.common.geom.shapes :as geom]
   [app.common.geom.point :as gpt]
   [app.common.geom.matrix :as gmt]
   [app.util.debug :refer [debug?]]
   [app.main.ui.workspace.shapes.outline :refer [outline]]
   [app.main.ui.measurements :as msr]
   [app.main.ui.workspace.shapes.path.editor :refer [path-editor]]))

(def rotation-handler-size 25)
(def resize-point-radius 4)
(def resize-point-circle-radius 10)
(def resize-point-rect-size 8)
(def resize-side-height 8)
(def selection-rect-color-normal "#1FDEA7")
(def selection-rect-color-component "#00E0FF")
(def selection-rect-width 1)

(mf/defc selection-rect [{:keys [transform rect zoom color]}]
  (let [{:keys [x y width height]} rect]
    [:rect.main
     {:x x
      :y y
      :width width
      :height height
      :transform transform
      :style {:stroke color
              :stroke-width (/ selection-rect-width zoom)
              :fill "transparent"}}]))

(defn- handlers-for-selection [{:keys [x y width height]}]
  [;; TOP-LEFT
   {:type :rotation
    :position :top-left
    :props {:cx x :cy y}}

   {:type :resize-point
    :position :top-left
    :props {:cx x :cy y}}

   {:type :rotation
    :position :top-right
    :props {:cx (+ x width) :cy y}}

   {:type :resize-point
    :position :top-right
    :props {:cx (+ x width) :cy y}}

   {:type :rotation
    :position :bottom-right
    :props {:cx (+ x width) :cy (+ y height)}}

   {:type :resize-point
    :position :bottom-right
    :props {:cx (+ x width) :cy (+ y height)}}

   {:type :rotation
    :position :bottom-left
    :props {:cx x :cy (+ y height)}}

   {:type :resize-point
    :position :bottom-left
    :props {:cx x :cy (+ y height)}}

   {:type :resize-side
    :position :top
    :props {:x x :y y :length width :angle 0 }}

   {:type :resize-side
    :position :right
    :props {:x (+ x width) :y y :length height :angle 90 }}

   {:type :resize-side
    :position :bottom
    :props {:x (+ x width) :y (+ y height) :length width :angle 180 }}

   {:type :resize-side
    :position :left
    :props {:x x :y (+ y height) :length height :angle 270 }}

   ])

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
            :fill (if (debug? :rotation-handler) "blue" "transparent")
            :transform transform
            :on-mouse-down on-rotate}]))

(mf/defc resize-point-handler
  [{:keys [cx cy zoom position on-resize transform rotation color overflow-text]}]
  (let [{cx' :x cy' :y} (gpt/transform (gpt/point cx cy) transform)
        rot-square (case position
                     :top-left 0
                     :top-right 90
                     :bottom-right 180
                     :bottom-left 270)]
    [:g.resize-handler
     [:circle {:r (/ resize-point-radius zoom)
               :style {:fillOpacity "1"
                       :strokeWidth "1px"
                       :vectorEffect "non-scaling-stroke"}
               :fill "#FFFFFF"
               :stroke (if (and (= position :bottom-right) overflow-text) "red" color)
               :cx cx'
               :cy cy'}]

     [:circle {:on-mouse-down #(on-resize {:x cx' :y cy'} %)
               :r (/ resize-point-circle-radius zoom)
               :fill (if (debug? :resize-handler) "red" "transparent")
               :cx cx'
               :cy cy'
               :style {:cursor (if (#{:top-left :bottom-right} position)
                                 (cur/resize-nesw rotation) (cur/resize-nwse rotation))}}]
     ]))

(mf/defc resize-side-handler [{:keys [x y length angle zoom position rotation transform on-resize]}]
  (let [res-point (if (#{:top :bottom} position)
                    {:y y}
                    {:x x})
        target-length (max 0 (- length (/ (* resize-point-rect-size 2) zoom)))
        width (if (< target-length 6) length target-length)
        height (/ resize-side-height zoom)]
    [:rect {:x (+ x (/ (- length width) 2))
            :y (- y (/ height 2))
            :width width
            :height height
            :transform (gmt/multiply transform
                                     (gmt/rotate-matrix angle (gpt/point x y)))
            :on-mouse-down #(on-resize res-point %)
            :style {:fill (if (debug? :resize-handler) "yellow" "transparent")
                    :cursor (if (#{:left :right} position)
                              (cur/resize-ew rotation)
                              (cur/resize-ns rotation)) }}]))
(mf/defc controls
  {::mf/wrap-props false}
  [props]
  (let [{:keys [overflow-text] :as shape} (obj/get props "shape")
        zoom  (obj/get props "zoom")
        color (obj/get props "color")
        on-resize (obj/get props "on-resize")
        on-rotate (obj/get props "on-rotate")
        current-transform (mf/deref refs/current-transform)

        selrect (:selrect shape)
        transform (geom/transform-matrix shape {:no-flip true})

        tr-shape (geom/transform-shape shape)]

    (when (not (#{:move :rotate} current-transform))
      [:g.controls

       ;; Selection rect
       [:& selection-rect {:rect selrect
                           :transform transform
                           :zoom zoom
                           :color color}]
       [:& outline {:shape tr-shape :color color}]

       ;; Handlers
       (for [{:keys [type position props]} (handlers-for-selection selrect)]
         (let [common-props {:key (str (name type) "-" (name position))
                             :zoom zoom
                             :position position
                             :on-rotate on-rotate
                             :on-resize (partial on-resize position)
                             :transform transform
                             :rotation (:rotation shape)
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
  [{:keys [shape zoom color] :as props}]
  (let [{:keys [x y width height]} shape]
    [:g.controls
     [:rect.main {:x x :y y
                  :transform (geom/transform-matrix shape)
                  :width width
                  :height height
                  :style {:stroke color
                          :stroke-width "0.5"
                          :stroke-opacity "1"
                          :fill "transparent"}}]]))

(mf/defc multiple-selection-handlers
  [{:keys [shapes selected zoom color show-distances] :as props}]
  (let [shape (geom/setup {:type :rect} (geom/selection-rect (->> shapes (map geom/transform-shape))))
        shape-center (geom/center-shape shape)

        hover-id (-> (mf/deref refs/current-hover) first)
        hover-id (when-not (d/seek #(= hover-id (:id %)) shapes) hover-id)
        hover-shape (mf/deref (refs/object-by-id hover-id))

        vbox (mf/deref refs/vbox)

        on-resize (fn [current-position initial-position event]
                    (dom/stop-propagation event)
                    (st/emit! (dw/start-resize current-position initial-position selected shape)))

        on-rotate #(do (dom/stop-propagation %)
                       (st/emit! (dw/start-rotate shapes)))]

    [:*
     [:& controls {:shape shape
                   :zoom zoom
                   :color color
                   :on-resize on-resize
                   :on-rotate on-rotate}]

     (when show-distances
       [:& msr/measurement {:bounds vbox
                            :selected-shapes shapes
                            :hover-shape hover-shape
                            :zoom zoom}])

     (when (debug? :selection-center)
       [:circle {:cx (:x shape-center) :cy (:y shape-center) :r 5 :fill "yellow"}])]))

(mf/defc single-selection-handlers
  [{:keys [shape zoom color show-distances] :as props}]
  (let [shape-id (:id shape)
        shape (geom/transform-shape shape)

        frame (mf/deref (refs/object-by-id (:frame-id shape)))
        frame (when-not (= (:id frame) uuid/zero) frame)
        vbox (mf/deref refs/vbox)

        hover-id (-> (mf/deref refs/current-hover) first)
        hover-id (when-not (= shape-id hover-id) hover-id)
        hover-shape (mf/deref (refs/object-by-id hover-id))

        shape' (if (debug? :simple-selection) (geom/setup {:type :rect} (geom/selection-rect [shape])) shape)
        on-resize (fn [current-position initial-position event]
                    (dom/stop-propagation event)
                    (st/emit! (dw/start-resize current-position initial-position #{shape-id} shape')))

        on-rotate
        #(do (dom/stop-propagation %)
             (st/emit! (dw/start-rotate [shape])))]
    [:*
     [:& controls {:shape shape'
                   :zoom zoom
                   :color color
                   :on-rotate on-rotate
                   :on-resize on-resize}]

     (when show-distances
       [:& msr/measurement {:bounds vbox
                            :frame frame
                            :selected-shapes [shape]
                            :hover-shape hover-shape
                            :zoom zoom}])]))

(mf/defc selection-handlers
  [{:keys [selected edition zoom show-distances] :as props}]
  (let [;; We need remove posible nil values because on shape
        ;; deletion many shape will reamin selected and deleted
        ;; in the same time for small instant of time
        shapes (->> (mf/deref (refs/objects-by-id selected))
                    (remove nil?))
        num (count shapes)
        {:keys [id type] :as shape} (first shapes)

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
                                       :show-distances show-distances}]

      (and (= type :text)
           (= edition (:id shape)))
      [:& text-edition-selection-handlers {:shape shape
                                           :zoom zoom
                                           :color color}]

      (and (= type :path)
           (= edition (:id shape)))
      [:& path-editor {:zoom zoom
                       :shape shape}]

      :else
      [:& single-selection-handlers {:shape shape
                                     :zoom zoom
                                     :color color
                                     :show-distances show-distances}])))
