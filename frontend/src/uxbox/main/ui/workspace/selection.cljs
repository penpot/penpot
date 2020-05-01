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
   [uxbox.main.data.workspace :as dw]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.util.dom :as dom]
   [uxbox.util.object :as obj]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.debug :refer [debug?]]))

;; --- Controls (Component)

(def ^:private handler-size-threshold
  "The size in pixels that shape width or height
  should reach in order to increase the handler
  control pointer radius from 4 to 6."
  60)

(mf/defc control-item
  {::mf/wrap-props false}
  [props]
  (let [class (obj/get props "class")
        on-click (obj/get props "on-click")
        r (obj/get props "r")
        cx (obj/get props "cx")
        cy (obj/get props "cy")]
    [:circle
     {:class-name class
      :on-mouse-down on-click
      :r r
      :style {:fillOpacity "1"
              :strokeWidth "1px"
            :vectorEffect "non-scaling-stroke"}
      :fill "#ffffff"
      :stroke "#1FDEA7"
      :cx cx
      :cy cy}]))

(def ^:private rotate-cursor-svg "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20px' height='20px' transform='rotate(%s)' viewBox='0 0 132.292 132.006'%3E%3Cpath d='M85.225 3.48c.034 4.989-.093 9.852-.533 14.78-29.218 5.971-54.975 27.9-63.682 56.683-1.51 2.923-1.431 7.632-3.617 9.546-5.825.472-11.544.5-17.393.45 11.047 15.332 20.241 32.328 32.296 46.725 5.632 1.855 7.155-5.529 10.066-8.533 8.12-12.425 17.252-24.318 24.269-37.482-6.25-.86-12.564-.88-18.857-1.057 5.068-17.605 19.763-31.81 37.091-37.122.181 6.402.206 12.825 1.065 19.184 15.838-9.05 30.899-19.617 45.601-30.257 2.985-4.77-3.574-7.681-6.592-9.791C111.753 17.676 98.475 8.889 85.23.046l-.005 3.435z'/%3E%3Cpath fill='%23fff' d='M92.478 23.995s-1.143.906-6.714 1.923c-29.356 5.924-54.352 30.23-59.717 59.973-.605 3.728-1.09 5.49-1.09 5.49l-11.483-.002s7.84 10.845 10.438 15.486c3.333 4.988 6.674 9.971 10.076 14.912a2266.92 2266.92 0 0019.723-29.326c-5.175-.16-10.35-.343-15.522-.572 3.584-27.315 26.742-50.186 53.91-54.096.306 5.297.472 10.628.631 15.91a2206.462 2206.462 0 0029.333-19.726c-9.75-6.7-19.63-13.524-29.483-20.12z'/%3E%3C/svg%3E\") 10 10, auto")

(defn rotation-cursor
  [angle]
  (str "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20px' height='20px' transform='rotate(" angle ")' viewBox='0 0 132.292 132.006'%3E%3Cpath d='M85.225 3.48c.034 4.989-.093 9.852-.533 14.78-29.218 5.971-54.975 27.9-63.682 56.683-1.51 2.923-1.431 7.632-3.617 9.546-5.825.472-11.544.5-17.393.45 11.047 15.332 20.241 32.328 32.296 46.725 5.632 1.855 7.155-5.529 10.066-8.533 8.12-12.425 17.252-24.318 24.269-37.482-6.25-.86-12.564-.88-18.857-1.057 5.068-17.605 19.763-31.81 37.091-37.122.181 6.402.206 12.825 1.065 19.184 15.838-9.05 30.899-19.617 45.601-30.257 2.985-4.77-3.574-7.681-6.592-9.791C111.753 17.676 98.475 8.889 85.23.046l-.005 3.435z'/%3E%3Cpath fill='%23fff' d='M92.478 23.995s-1.143.906-6.714 1.923c-29.356 5.924-54.352 30.23-59.717 59.973-.605 3.728-1.09 5.49-1.09 5.49l-11.483-.002s7.84 10.845 10.438 15.486c3.333 4.988 6.674 9.971 10.076 14.912a2266.92 2266.92 0 0019.723-29.326c-5.175-.16-10.35-.343-15.522-.572 3.584-27.315 26.742-50.186 53.91-54.096.306 5.297.472 10.628.631 15.91a2206.462 2206.462 0 0029.333-19.726c-9.75-6.7-19.63-13.524-29.483-20.12z'/%3E%3C/svg%3E\") 10 10, auto"))

(def rotation-handler-positions
  #{:top-left :top-right :bottom-left :bottom-right})

(mf/defc rotation-handler
  {::mf/wrap-props false}
  [props]
  (let [cx (obj/get props "cx")
        cy (obj/get props "cy")
        position (obj/get props "position")
        on-mouse-down (obj/get props "on-mouse-down")
        rotation (obj/get props "rotation")
        zoom (obj/get props "zoom")]
    (when (contains? rotation-handler-positions position)
      (let [size (/ 20 zoom)
            rotation (or rotation 0)
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
                :fill (if (debug? :rotation-handler) "red" "transparent")
                :transform (gmt/rotate-matrix rotation (gpt/point cx cy))
                :on-mouse-down (or on-mouse-down (fn []))}]))))

(mf/defc controls
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")
        zoom  (obj/get props "zoom")
        on-resize (obj/get props "on-resize")
        on-rotate (obj/get props "on-rotate")
        current-transform (mf/deref refs/current-transform)
        {:keys [x y width height rotation] :as shape} (geom/shape->rect-shape shape)

        radius (if (> (max width height) handler-size-threshold) 4.0 4.0)
        transform (geom/transform-matrix shape)
        resize-handlers {:top          [(+ x (/ width 2 )) y]
                         :right        [(+ x width) (+ y (/ height 2))]
                         :bottom       [(+ x (/ width 2)) (+ y height)]
                         :left         [x (+ y (/ height 2))]
                         :top-left     [x y]
                         :top-right    [(+ x width) y]
                         :bottom-left  [x (+ y height)]
                         :bottom-right [(+ x width) (+ y height)]}]

    [:g.controls
     (when (not (#{:move :rotate :resize} current-transform))
      [:rect.main {:transform transform
                   :x (- x 1) :y (- y 1)
                   :width (+ width 2)
                   :height (+ height 2)
                   :style {:stroke "#1FDEA7"
                           :stroke-width "1"
                           :fill "transparent"}}])

     (when (not (#{:move :rotate} current-transform))
       (for [[position [cx cy]] resize-handlers]
         (let [tp (gpt/transform (gpt/point cx cy) transform)]
           [:* {:key (name position)}
            [:& rotation-handler {:key (str "rotation-" (name position))
                                  :cx (:x tp)
                                  :cy (:y tp)
                                  :position position
                                  :rotation (:rotation shape)
                                  :zoom zoom
                                  :on-mouse-down on-rotate}]

          [:& control-item {:class (name position)
                            :on-click #(on-resize position %)
                            :r (/ radius zoom)
                            :cx (:x tp)
                            :cy (:y tp)}]]))]))

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
