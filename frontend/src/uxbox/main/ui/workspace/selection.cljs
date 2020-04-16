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
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.geom.matrix :as gmt]))

(defn- apply-zoom
  [point]
  (gpt/divide point (gpt/point @refs/selected-zoom)))

;; --- Resize & Rotate

(defn- start-resize
  [vid ids shape]
  (letfn [(resize [shape [point lock?]]
            (let [result (geom/resize-shape vid shape point lock?)
                  scale (geom/calculate-scale-ratio shape result)
                  mtx (geom/generate-resize-matrix vid shape scale)]
              (rx/of (dw/assoc-resize-modifier-in-bulk ids mtx))))

          ;; Unifies the instantaneous proportion lock modifier
          ;; activated by Ctrl key and the shapes own proportion
          ;; lock flag that can be activated on element options.
          (normalize-proportion-lock [[point ctrl?]]
            (let [proportion-lock? (:proportion-lock shape)]
              [point (or proportion-lock? ctrl?)]))

          ;; Applies alginment to point if it is currently
          ;; activated on the current workspace
          ;; (apply-grid-alignment [point]
          ;;   (if @refs/selected-alignment
          ;;     (uwrk/align-point point)
          ;;     (rx/of point)))
          ]
    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [shape  (->> (geom/shape->rect-shape shape)
                          (geom/size))
              stoper (rx/filter ms/mouse-up? stream)]
          (rx/concat
           (->> ms/mouse-position
                (rx/map apply-zoom)
                ;; (rx/mapcat apply-grid-alignment)
                (rx/with-latest vector ms/mouse-position-ctrl)
                (rx/map normalize-proportion-lock)
                (rx/mapcat (partial resize shape))
                (rx/take-until stoper))
           (rx/of (dw/materialize-resize-modifier-in-bulk ids))))))))

(defn start-rotate
  [shapes]
  (ptk/reify ::start-rotate
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter ms/mouse-up? stream)
            group  (geom/selection-rect shapes)
            group-center (gpt/center group)
            initial-angle (gpt/angle @ms/mouse-position group-center)
            calculate-angle (fn [pos ctrl?]
                              (let [angle (- (gpt/angle pos group-center) initial-angle)
                                    angle (if (neg? angle) (+ 360 angle) angle)
                                    modval (mod angle 90)
                                    angle (if ctrl?
                                            (if (< 50 modval)
                                              (+ angle (- 90 modval))
                                              (- angle modval))
                                            angle)
                                    angle (if (= angle 360)
                                            0
                                            angle)]
                                angle))]
        (rx/concat
         (->> ms/mouse-position
              (rx/map apply-zoom)
              (rx/with-latest vector ms/mouse-position-ctrl)
              (rx/map (fn [[pos ctrl?]]
                        (let [delta-angle (calculate-angle pos ctrl?)]
                          (dw/apply-rotation delta-angle shapes))))


              (rx/take-until stoper))
         (rx/of (dw/materialize-rotation shapes))
         )))))

;; --- Controls (Component)

(def ^:private handler-size-threshold
  "The size in pixels that shape width or height
  should reach in order to increase the handler
  control pointer radius from 4 to 6."
  60)

(mf/defc control-item
  [{:keys [class on-click r cy cx] :as props}]
  [:circle
   {:class-name class
    :on-mouse-down on-click
    :r r
    :style {:fillOpacity "1"
            :strokeWidth "2px"
            :vectorEffect "non-scaling-stroke"}
    :fill "#ffffff"
    :stroke "#1FDEA7"
    :cx cx
    :cy cy}])

(def ^:private rotate-cursor-svg "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20px' height='20px' transform='rotate(%s)' viewBox='0 0 132.292 132.006'%3E%3Cpath d='M85.225 3.48c.034 4.989-.093 9.852-.533 14.78-29.218 5.971-54.975 27.9-63.682 56.683-1.51 2.923-1.431 7.632-3.617 9.546-5.825.472-11.544.5-17.393.45 11.047 15.332 20.241 32.328 32.296 46.725 5.632 1.855 7.155-5.529 10.066-8.533 8.12-12.425 17.252-24.318 24.269-37.482-6.25-.86-12.564-.88-18.857-1.057 5.068-17.605 19.763-31.81 37.091-37.122.181 6.402.206 12.825 1.065 19.184 15.838-9.05 30.899-19.617 45.601-30.257 2.985-4.77-3.574-7.681-6.592-9.791C111.753 17.676 98.475 8.889 85.23.046l-.005 3.435z'/%3E%3Cpath fill='%23fff' d='M92.478 23.995s-1.143.906-6.714 1.923c-29.356 5.924-54.352 30.23-59.717 59.973-.605 3.728-1.09 5.49-1.09 5.49l-11.483-.002s7.84 10.845 10.438 15.486c3.333 4.988 6.674 9.971 10.076 14.912a2266.92 2266.92 0 0019.723-29.326c-5.175-.16-10.35-.343-15.522-.572 3.584-27.315 26.742-50.186 53.91-54.096.306 5.297.472 10.628.631 15.91a2206.462 2206.462 0 0029.333-19.726c-9.75-6.7-19.63-13.524-29.483-20.12z'/%3E%3C/svg%3E\") 10 10, auto")

(mf/defc rotation-handler
  [{:keys [cx cy position on-mouse-down rotation]}]
  (when (#{:top-left :top-right :bottom-left :bottom-right} position)
    (let [size 20
          rotation (or rotation 0)
          x (- cx (if (#{:top-left :bottom-left} position) size 0))
          y (- cy (if (#{:top-left :top-right} position) size 0))
          angle (case position
                  :top-left 0
                  :top-right 90
                  :bottom-right 180
                  :bottom-left 270)]
      [:rect {:style {:cursor (str/format rotate-cursor-svg (str (+ rotation angle)))}
              :x x
              :y y
              :width size
              :height size
              :fill "transparent"
              :on-mouse-down (or on-mouse-down (fn []))}])))

(mf/defc controls
  [{:keys [shape zoom on-resize on-rotate] :as props}]
  (let [{:keys [x y width height rotation] :as shape} (geom/shape->rect-shape shape)
        radius (if (> (max width height) handler-size-threshold) 6.0 4.0)
        transform (geom/rotation-matrix shape)

        resize-handlers {:top          [(+ x (/ width 2 )) (- y 2)]
                         :right        [(+ x width 1) (+ y (/ height 2))]
                         :bottom       [(+ x (/ width 2)) (+ y height 2)]
                         :left         [(- x 3) (+ y (/ height 2))]
                         :top-left     [x y]
                         :top-right    [(+ x width) y]
                         :bottom-left  [x (+ y height)]
                         :bottom-right [(+ x width) (+ y height)]}]

    [:g.controls {:transform transform}
     [:rect.main {:x x :y y
                  :width width
                  :height height
                  :stroke-dasharray (str (/ 8.0 zoom) "," (/ 5 zoom))
                  :style {:stroke "#1FDEA7"
                          :fill "transparent"
                          :stroke-opacity "1"}}]

     (for [[position [cx cy]] resize-handlers]
       [:* {:key (str "fragment-" (name position))}
        [:& rotation-handler {:key (str "rotation-" (name position))
                              :cx cx
                              :cy cy
                              :position position
                              :rotation (:rotation shape)
                              :on-mouse-down on-rotate}]

        [:& control-item {:key (str "resize-" (name position))
                          :class (name position)
                          :on-click #(on-resize position %)
                          :r (/ radius zoom)
                          :cx cx
                          :cy cy}]])]))

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

    (let [displacement (:displacement modifiers)
          segments (cond->> (:segments shape)
                     displacement (map #(gpt/transform % displacement)))]
      [:g.controls
       (for [[index {:keys [x y]}] (map-indexed vector segments)]
         [:circle {:cx x :cy y
                   :r (/ 6.0 zoom)
                   :key index
                   :on-mouse-down #(on-mouse-down % index)
                   :fill "#ffffff"
                   :stroke "#1FDEA7"
                   :style {:cursor "pointer"}}])])))

;; TODO: add specs for clarity

(mf/defc text-edition-selection-handlers
  [{:keys [shape zoom] :as props}]
  (let [{:keys [x y width height] :as shape} shape]
    [:g.controls
     [:rect.main {:x x :y y
                  :width width
                  :height height
                  ;; :stroke-dasharray (str (/ 5.0 zoom) "," (/ 5 zoom))
                  :style {:stroke "#1FDEA7"
                          :stroke-width "0.5"
                          :stroke-opacity "1"
                          :fill "transparent"}}]]))

(mf/defc multiple-selection-handlers
  [{:keys [shapes selected zoom] :as props}]
  (let [shape (geom/selection-rect shapes)
        on-resize #(do (dom/stop-propagation %2)
                       (st/emit! (start-resize %1 selected shape)))

        on-rotate #(do (dom/stop-propagation %)
                       (st/emit! (start-rotate shapes)))]

    [:& controls {:shape shape
                  :zoom zoom
                  :on-resize on-resize
                  :on-rotate on-rotate}]))

(mf/defc single-selection-handlers
  [{:keys [shape zoom objects] :as props}]
  (let [shape (geom/transform-shape shape)
        on-resize #(do (dom/stop-propagation %2)
                       (st/emit! (start-resize %1 #{(:id shape)} shape)))
        on-rotate #(do (dom/stop-propagation %)
                       (st/emit! (start-rotate [shape])))]

    [:& controls {:shape shape
                  :zoom zoom
                  :on-rotate on-rotate
                  :on-resize on-resize}]))

(mf/defc selection-handlers
  [{:keys [selected edition zoom] :as props}]
  (let [data    (mf/deref refs/workspace-data)
        objects (:objects data)

        ;; We need remove posible nil values because on shape
        ;; deletion many shape will reamin selected and deleted
        ;; in the same time for small instant of time
        shapes (->> (map #(get objects %) selected)
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
                                     :objects objects
                                     :zoom zoom}])))
