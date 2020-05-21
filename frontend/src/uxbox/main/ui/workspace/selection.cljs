;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.selection
  "Selection handlers component."
  (:require
   [beicon.core :as rx]
   [lentes.core :as l]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [rumext.util :refer [map->obj]]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.util.dom :as dom]
   [uxbox.util.object :as obj]
   [uxbox.common.geom.shapes :as geom]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.geom.matrix :as gmt]
   [uxbox.util.debug :refer [debug?]]))

(defn rotation-cursor [angle]
  (str "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20px' height='20px' transform='rotate(" angle ")' viewBox='0 0 132.292 132.006'%3E%3Cpath d='M85.225 3.48c.034 4.989-.093 9.852-.533 14.78-29.218 5.971-54.975 27.9-63.682 56.683-1.51 2.923-1.431 7.632-3.617 9.546-5.825.472-11.544.5-17.393.45 11.047 15.332 20.241 32.328 32.296 46.725 5.632 1.855 7.155-5.529 10.066-8.533 8.12-12.425 17.252-24.318 24.269-37.482-6.25-.86-12.564-.88-18.857-1.057 5.068-17.605 19.763-31.81 37.091-37.122.181 6.402.206 12.825 1.065 19.184 15.838-9.05 30.899-19.617 45.601-30.257 2.985-4.77-3.574-7.681-6.592-9.791C111.753 17.676 98.475 8.889 85.23.046l-.005 3.435z'/%3E%3Cpath fill='%23fff' d='M92.478 23.995s-1.143.906-6.714 1.923c-29.356 5.924-54.352 30.23-59.717 59.973-.605 3.728-1.09 5.49-1.09 5.49l-11.483-.002s7.84 10.845 10.438 15.486c3.333 4.988 6.674 9.971 10.076 14.912a2266.92 2266.92 0 0019.723-29.326c-5.175-.16-10.35-.343-15.522-.572 3.584-27.315 26.742-50.186 53.91-54.096.306 5.297.472 10.628.631 15.91a2206.462 2206.462 0 0029.333-19.726c-9.75-6.7-19.63-13.524-29.483-20.12z'/%3E%3C/svg%3E\") 10 10, auto"))

(def rotation-handler-size 25)
(def resize-point-radius 4)
(def resize-point-circle-radius 10)
(def resize-point-rect-size 20)
(def resize-side-height 8)
(def selection-rect-color "#1FDEA7")
(def selection-rect-width 1)

(mf/defc selection-rect [{:keys [transform rect zoom]}]
  (let [{:keys [x y width height]} rect]
    [:rect.main
     {:x x
      :y y
      :width width
      :height height
      :transform transform
      :style {:stroke selection-rect-color
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

   ;; TOP
   {:type :resize-side
    :position :top
    :props {:x x :y y :length width :angle 0 }}

   ;; TOP-RIGHT
   {:type :rotation
    :position :top-right
    :props {:cx (+ x width) :cy y}}

   {:type :resize-point
    :position :top-right
    :props {:cx (+ x width) :cy y}}

   ;; RIGHT
   {:type :resize-side
    :position :right
    :props {:x (+ x width) :y y :length height :angle 90 }}

   ;; BOTTOM-RIGHT
   {:type :rotation
    :position :bottom-right
    :props {:cx (+ x width) :cy (+ y height)}}

   {:type :resize-point
    :position :bottom-right
    :props {:cx (+ x width) :cy (+ y height)}}

   ;; BOTTOM
   {:type :resize-side
    :position :bottom
    :props {:x (+ x width) :y (+ y height) :length width :angle 180 }}

   ;; BOTTOM-LEFT
   {:type :rotation
    :position :bottom-left
    :props {:cx x :cy (+ y height)}}

   {:type :resize-point
    :position :bottom-left
    :props {:cx x :cy (+ y height)}}

   ;; LEFT
   {:type :resize-side
    :position :left
    :props {:x x :y (+ y height) :length height :angle 270 }}])

(mf/defc rotation-handler [{:keys [cx cy transform position rotation zoom on-rotate]}]
  (let [size (/ rotation-handler-size zoom)
        x (- cx (if (#{:top-left :bottom-left} position) size 0))
        y (- cy (if (#{:top-left :top-right} position) size 0))
        angle (case position
                :top-left 0
                :top-right 90
                :bottom-right 180
                :bottom-left 270)]
    [:rect {:style {:cursor (rotation-cursor (+ rotation angle))}
            :x x
            :y y
            :width size
            :height size
            :fill (if (debug? :rotation-handler) "blue" "transparent")
            :transform transform
            :on-mouse-down on-rotate}]))

(mf/defc resize-point-handler [{:keys [cx cy zoom position on-resize transform]}]
  (let [{cx' :x cy' :y} (gpt/transform (gpt/point cx cy) transform)
        rot-square (case position
                     :top-left 0
                     :top-right 90
                     :bottom-right 180
                     :bottom-left 270)]
    [:g.resize-handler
     [:circle {:class (name position)
               :r (/ resize-point-radius zoom)
               :style {:fillOpacity "1"
                       :strokeWidth "1px"
                       :vectorEffect "non-scaling-stroke"}
               :fill "#FFFFFF"
               :stroke "#1FDEA7"
               :cx cx'
               :cy cy'}]

     [:rect {:class (name position)
             :x cx
             :y cy
             :width (/ resize-point-rect-size zoom)
             :height (/ resize-point-rect-size zoom)
             :fill (if (debug? :resize-handler) "red" "transparent")
             :on-mouse-down on-resize
             :transform (gmt/multiply transform
                                      (gmt/rotate-matrix rot-square (gpt/point cx cy)))}]
     [:circle {:class (name position)
               :on-mouse-down on-resize
               :r (/ resize-point-circle-radius zoom)
               :fill (if (debug? :resize-handler) "red" "transparent")
               :cx cx'
               :cy cy'}]
     ]))

(mf/defc resize-side-handler [{:keys [x y length angle zoom position transform on-resize]}]
  [:rect {:x (+ x (/ resize-point-rect-size zoom))
          :y (- y (/ resize-side-height 2 zoom))
          :width (max 0 (- length (/ (* resize-point-rect-size 2) zoom)))
          :height (/ resize-side-height zoom)
          :transform (gmt/multiply transform
                                   (gmt/rotate-matrix angle (gpt/point x y)))
          :on-mouse-down on-resize
          :style {:fill (if (debug? :resize-handler) "yellow" "transparent")
                  :cursor (if (#{:left :right} position)
                            "ew-resize"
                            "ns-resize") }}])

(mf/defc controls
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")
        zoom  (obj/get props "zoom")
        on-resize (obj/get props "on-resize")
        on-rotate (obj/get props "on-rotate")
        current-transform (mf/deref refs/current-transform)

        selrect (geom/shape->rect-shape shape)
        transform (geom/transform-matrix shape)]

    (when (not (#{:move :rotate} current-transform))
      [:g.controls

       ;; Selection rect
       [:& selection-rect {:rect shape
                           :transform transform
                           :zoom zoom}]

       ;; Handlers
       (for [{:keys [type position props]} (handlers-for-selection selrect)]
         (let [common-props {:key (str (name type) "-" (name position))
                             :zoom zoom
                             :position position
                             :on-rotate on-rotate
                             :on-resize (partial on-resize position)
                             :transform transform
                             :rotation (:rotation shape)}
               props (->> props (merge common-props) map->obj)]
           (case type
             :rotation (when (not= :frame (:type shape)) [:> rotation-handler props])
             :resize-point [:> resize-point-handler props]
             :resize-side [:> resize-side-handler props])))])))

;; --- Selection Handlers (Component)
(mf/defc path-edition-selection-handlers
  [{:keys [shape modifiers zoom] :as props}]
  (letfn [(on-mouse-down [event index]
            (dom/stop-propagation event)
            ;; TODO: this need code ux refactor
            (let [stoper (get-edition-stream-stoper)
                  stream (->> (ms/mouse-position-deltas @ms/mouse-position)
                              (rx/take-until stoper))]
              ;; (when @refs/selected-alignment
              ;;   (st/emit! (dw/initial-path-point-align (:id shape) index)))
              (rx/subscribe stream #(on-handler-move % index))))

          (get-edition-stream-stoper []
            (let [stoper? #(and (ms/mouse-event? %) (= (:type %) :up))]
              (rx/merge
               (rx/filter stoper? st/stream)
               (->> st/stream
                    (rx/filter #(= % :interrupt))
                    (rx/take 1)))))

          (on-handler-move [delta index]
            (st/emit! (dw/update-path (:id shape) index delta)))]

    (let [transform (geom/transform-matrix shape)
          displacement (:displacement modifiers)
          segments (cond->> (:segments shape)
                     displacement (map #(gpt/transform % displacement)))]
      [:g.controls
       (for [[index {:keys [x y]}] (map-indexed vector segments)]
         (let [{:keys [x y]} (gpt/transform (gpt/point x y) transform)]
           [:circle {:cx x :cy y
                     :r (/ 6.0 zoom)
                     :key index
                     :on-mouse-down #(on-mouse-down % index)
                     :fill "#ffffff"
                     :stroke "#1FDEA7"
                     :style {:cursor "pointer"}}]))])))

;; TODO: add specs for clarity

(mf/defc text-edition-selection-handlers
  [{:keys [shape zoom] :as props}]
  (let [{:keys [x y width height]} shape]
    [:g.controls
     [:rect.main {:x x :y y
                  :transform (geom/transform-matrix shape)
                  :width width
                  :height height
                  :style {:stroke "#1FDEA7"
                          :stroke-width "0.5"
                          :stroke-opacity "1"
                          :fill "transparent"}}]]))

(mf/defc multiple-selection-handlers
  [{:keys [shapes selected zoom] :as props}]
  (let [shape (geom/selection-rect shapes)
        shape-center (geom/center shape)
        on-resize #(do (dom/stop-propagation %2)
                       (st/emit! (dw/start-resize %1 selected shape)))

        on-rotate #(do (dom/stop-propagation %)
                       (st/emit! (dw/start-rotate shapes)))]

    [:*
     [:& controls {:shape shape
                   :zoom zoom
                   :on-resize on-resize
                   :on-rotate on-rotate}]
     (when (debug? :selection-center)
       [:circle {:cx (:x shape-center) :cy (:y shape-center) :r 5 :fill "yellow"}])]))

(mf/defc single-selection-handlers
  [{:keys [shape zoom] :as props}]
  (let [shape-id (:id shape)
        shape (geom/transform-shape shape)
        shape' (if (debug? :simple-selection) (geom/selection-rect [shape]) shape)

        on-resize
        #(do (dom/stop-propagation %2)
             (st/emit! (dw/start-resize %1 #{shape-id} shape')))

        on-rotate
        #(do (dom/stop-propagation %)
             (st/emit! (dw/start-rotate [shape])))]

    [:*
     [:& controls {:shape shape'
                   :zoom zoom
                   :on-rotate on-rotate
                   :on-resize on-resize}]]))

(mf/defc selection-handlers
  [{:keys [selected edition zoom] :as props}]
  (let [;; We need remove posible nil values because on shape
        ;; deletion many shape will reamin selected and deleted
        ;; in the same time for small instant of time
        shapes (->> (mf/deref (refs/objects-by-id selected))
                    (remove nil?))
        num (count shapes)
        {:keys [id type] :as shape} (first shapes)]
    (cond
      (zero? num)
      nil

      (> num 1)
      [:& multiple-selection-handlers {:shapes shapes
                                       :selected selected
                                       :zoom zoom}]

      (and (= type :text)
           (= edition (:id shape)))
      [:& text-edition-selection-handlers {:shape shape
                                           :zoom zoom}]
      (and (or (= type :path)
               (= type :curve))
           (= edition (:id shape)))
      [:& path-edition-selection-handlers {:shape shape
                                           :zoom zoom}]

      :else
      [:& single-selection-handlers {:shape shape
                                     :zoom zoom}])))
