;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.gradients
  "Gradients handlers and renders"
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.data.workspace.colors :as dc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.dom :as dom]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def gradient-line-stroke-width 2)
(def gradient-line-stroke-color "white")
(def gradient-square-width 15)
(def gradient-square-radius 2)
(def gradient-square-stroke-width 2)
(def gradient-width-handler-radius 5)
(def gradient-width-handler-color "white")
(def gradient-square-stroke-color "white")
(def gradient-square-stroke-color-selected "#1FDEA7")

(def editing-spot-ref
  (l/derived (l/in [:workspace-local :editing-stop]) st/state))

(def current-gradient-ref
  (l/derived (l/in [:workspace-local :current-gradient]) st/state))

(mf/defc shadow [{:keys [id x y width height offset]}]
  [:filter {:id id
            :x x
            :y y
            :width width
            :height height
            :filterUnits "userSpaceOnUse"
            :color-interpolation-filters "sRGB"}
   [:feFlood {:flood-opacity "0" :result "BackgroundImageFix"}]
   [:feColorMatrix {:in "SourceAlpha" :type "matrix" :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"}]
   [:feOffset {:dy offset}]
   [:feGaussianBlur {:stdDeviation "1"}]
   [:feColorMatrix {:type "matrix" :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0.15 0"}]
   [:feBlend {:mode "normal" :in2 "BackgroundImageFix" :result id}]
   [:feBlend {:mode "normal" :in "SourceGraphic" :in2 id :result "shape"}]])

(mf/defc gradient-line-drop-shadow-filter [{:keys [id zoom from-p to-p]}]
  [:& shadow
   {:id id
    :x (min (- (:x from-p) (/ 2 zoom))
            (- (:x to-p) (/ 2 zoom)))
    :y (min (- (:y from-p) (/ 2 zoom))
            (- (:y to-p) (/ 2 zoom)))
    :width (+ (mth/abs (- (:x to-p) (:x from-p))) (/ 4 zoom))
    :height (+ (mth/abs (- (:y to-p) (:y from-p))) (/ 4 zoom))
    :offset (/ 2 zoom)}])


(mf/defc gradient-square-drop-shadow-filter [{:keys [id zoom point]}]
  [:& shadow
   {:id id
    :x (- (:x point) (/ gradient-square-width zoom 2) 2)
    :y (- (:y point) (/ gradient-square-width zoom 2) 2)
    :width (+ (/ gradient-square-width zoom) (/ 2 zoom) 4)
    :height (+ (/ gradient-square-width zoom) (/ 2 zoom) 4)
    :offset (/ 2 zoom)}])

(mf/defc gradient-width-handler-shadow-filter [{:keys [id zoom point]}]
  [:& shadow
   {:id id
    :x (- (:x point) (/ gradient-width-handler-radius zoom) 2)
    :y (- (:y point) (/ gradient-width-handler-radius zoom) 2)
    :width (+ (/ (* 2 gradient-width-handler-radius) zoom) (/ 2 zoom) 4)
    :height (+ (/ (* 2 gradient-width-handler-radius) zoom) (/ 2 zoom) 4)
    :offset (/ 2 zoom)}])

(def checkerboard "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA8AAAAPCAIAAAC0tAIdAAACvUlEQVQoFQGyAk39AeLi4gAAAAAAAB0dHQAAAAAAAOPj4wAAAAAAAB0dHQAAAAAAAOPj4wAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB////AAAAAAAA4+PjAAAAAAAAHR0dAAAAAAAA4+PjAAAAAAAAHR0dAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAATj4+MAAAAAAAAdHR0AAAAAAADj4+MAAAAAAAAdHR0AAAAAAADj4+MAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAjScaa0cU7nIAAAAASUVORK5CYII=")

#_(def checkerboard "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAABHNCSVQICAgIfAhkiAAAADFJREFUOE9jZGBgEAFifOANPknGUQMYhkkYEEgG+NMJKAwIAbwJbdQABnBCIgRoG4gAIF8IsXB/Rs4AAAAASUVORK5CYII=")

(mf/defc gradient-color-handler
  [{:keys [filter-id zoom point color angle selected
           on-click on-mouse-down on-mouse-up]}]
  [:g {:filter (str/fmt "url(#%s)" filter-id)
       :transform (gmt/rotate-matrix angle point)}

   [:image {:href checkerboard
            :x (- (:x point) (/ gradient-square-width 2 zoom))
            :y (- (:y point) (/ gradient-square-width 2 zoom))
            :width (/ gradient-square-width zoom)
            :height (/ gradient-square-width zoom)}]

   [:rect {:x (- (:x point) (/ gradient-square-width 2 zoom))
           :y (- (:y point) (/ gradient-square-width 2 zoom))
           :rx (/ gradient-square-radius zoom)
           :width (/ gradient-square-width zoom 2)
           :height (/ gradient-square-width zoom)
           :fill (:value color)
           :on-click (partial on-click :to-p)
           :on-mouse-down (partial on-mouse-down :to-p)
           :on-mouse-up (partial on-mouse-up :to-p)}]

   [:rect {:data-allow-click-modal "colorpicker"
           :x (- (:x point) (/ gradient-square-width 2 zoom))
           :y (- (:y point) (/ gradient-square-width 2 zoom))
           :rx (/ gradient-square-radius zoom)
           :width (/ gradient-square-width zoom)
           :height (/ gradient-square-width zoom)
           :stroke (if selected "#31EFB8" "white")
           :stroke-width (/ gradient-square-stroke-width zoom)
           :fill (:value color)
           :fill-opacity (:opacity color)
           :on-click on-click
           :on-mouse-down on-mouse-down
           :on-mouse-up on-mouse-up}]])

(mf/defc gradient-handler-transformed
  [{:keys [from-p to-p width-p from-color to-color zoom editing
           on-change-start on-change-finish on-change-width]}]
  (let [moving-point (mf/use-var nil)
        angle (+ 90 (gpt/angle from-p to-p))

        on-click (fn [position event]
                   (dom/stop-propagation event)
                   (dom/prevent-default event)
                   (when (#{:from-p :to-p} position)
                     (st/emit! (dc/select-gradient-stop (case position
                                                          :from-p 0
                                                          :to-p 1)))))

        on-mouse-down (fn [position event]
                        (dom/stop-propagation event)
                        (dom/prevent-default event)
                        (reset! moving-point position)
                        (when (#{:from-p :to-p} position)
                          (st/emit! (dc/select-gradient-stop (case position
                                                               :from-p 0
                                                               :to-p 1)))))

        on-mouse-up (fn [_position event]
                      (dom/stop-propagation event)
                      (dom/prevent-default event)
                      (reset! moving-point nil))]

    (mf/use-effect
     (mf/deps @moving-point from-p to-p width-p)
     (fn []
       (let [subs (->> st/stream
                       (rx/filter ms/pointer-event?)
                       (rx/filter #(= :viewport (:source %)))
                       (rx/map :pt)
                       (rx/subs #(case @moving-point
                                   :from-p (when on-change-start (on-change-start %))
                                   :to-p (when on-change-finish (on-change-finish %))
                                   :width-p (when on-change-width
                                              (let [width-v (gpt/unit (gpt/to-vec from-p width-p))
                                                    distance (gpt/point-line-distance % from-p to-p)
                                                    new-width-p (gpt/add
                                                                 from-p
                                                                 (gpt/multiply width-v (gpt/point distance)))]
                                                (on-change-width new-width-p)))
                                   nil)))]
         (fn [] (rx/dispose! subs)))))
    [:g.gradient-handlers
     [:defs
      [:& gradient-line-drop-shadow-filter {:id "gradient_line_drop_shadow" :from-p from-p :to-p to-p :zoom zoom}]
      [:& gradient-line-drop-shadow-filter {:id "gradient_width_line_drop_shadow" :from-p from-p :to-p width-p :zoom zoom}]
      [:& gradient-square-drop-shadow-filter {:id "gradient_square_from_drop_shadow" :point from-p :zoom zoom}]
      [:& gradient-square-drop-shadow-filter {:id "gradient_square_to_drop_shadow" :point to-p :zoom zoom}]
      [:& gradient-width-handler-shadow-filter {:id "gradient_width_handler_drop_shadow" :point width-p :zoom zoom}]]

     [:g {:filter "url(#gradient_line_drop_shadow)"}
      [:line {:x1 (:x from-p)
              :y1 (:y from-p)
              :x2 (:x to-p)
              :y2 (:y to-p)
              :stroke gradient-line-stroke-color
              :stroke-width (/ gradient-line-stroke-width zoom)}]]

     (when width-p
       [:g {:filter "url(#gradient_width_line_drop_shadow)"}
        [:line {:x1 (:x from-p)
                :y1 (:y from-p)
                :x2 (:x width-p)
                :y2 (:y width-p)
                :stroke gradient-line-stroke-color
                :stroke-width (/ gradient-line-stroke-width zoom)}]])

     (when width-p
       [:g {:filter "url(#gradient_width_handler_drop_shadow)"}
        [:circle {:data-allow-click-modal "colorpicker"
                  :cx (:x width-p)
                  :cy (:y width-p)
                  :r (/ gradient-width-handler-radius zoom)
                  :fill gradient-width-handler-color
                  :on-mouse-down (partial on-mouse-down :width-p)
                  :on-mouse-up (partial on-mouse-up :width-p)}]])

     [:& gradient-color-handler
      {:selected (or (not editing) (= editing 0))
       :filter-id "gradient_square_from_drop_shadow"
       :zoom zoom
       :point from-p
       :color from-color
       :angle angle
       :on-click (partial on-click :from-p)
       :on-mouse-down (partial on-mouse-down :from-p)
       :on-mouse-up (partial on-mouse-up :from-p)}]

     [:& gradient-color-handler
      {:selected (= editing 1)
       :filter-id "gradient_square_to_drop_shadow"
       :zoom zoom
       :point to-p
       :color to-color
       :angle angle
       :on-click (partial on-click :to-p)
       :on-mouse-down (partial on-mouse-down :to-p)
       :on-mouse-up (partial on-mouse-up :to-p)}]]))


(mf/defc gradient-handlers
  [{:keys [id zoom]}]
  (let [shape (mf/deref (refs/object-by-id id))
        gradient (mf/deref current-gradient-ref)
        editing-spot (mf/deref editing-spot-ref)

        transform (gsh/transform-matrix shape)
        transform-inverse (gsh/inverse-transform-matrix shape)

        {:keys [x y width height] :as sr} (:selrect shape)

        [{start-color :color start-opacity :opacity}
         {end-color :color end-opacity :opacity}] (:stops gradient)

        from-p (-> (gpt/point (+ x (* width (:start-x gradient)))
                              (+ y (* height (:start-y gradient))))

                   (gpt/transform transform))

        to-p   (-> (gpt/point (+ x (* width (:end-x gradient)))
                              (+ y (* height (:end-y gradient))))
                   (gpt/transform transform))

        gradient-vec (gpt/to-vec from-p to-p)
        gradient-length (gpt/length gradient-vec)

        width-v (-> gradient-vec
                    (gpt/normal-left)
                    (gpt/multiply (gpt/point (* (:width gradient) (/ gradient-length (/ height 2) ))))
                    (gpt/multiply (gpt/point (/ width 2))))

        width-p (gpt/add from-p width-v)

        change! (fn [changes]
                  (st/emit! (dc/update-gradient changes)))

        on-change-start (fn [point]
                          (let [point (gpt/transform point transform-inverse)
                                start-x (/ (- (:x point) x) width)
                                start-y (/ (- (:y point) y) height)
                                start-x (mth/precision start-x 2)
                                start-y (mth/precision start-y 2)]
                            (change! {:start-x start-x :start-y start-y})))

        on-change-finish (fn [point]
                           (let [point (gpt/transform point transform-inverse)
                                 end-x (/ (- (:x point) x) width)
                                 end-y (/ (- (:y point) y) height)
                                 end-x (mth/precision end-x 2)
                                 end-y (mth/precision end-y 2)]
                             (change! {:end-x end-x :end-y end-y})))

        on-change-width (fn [point]
                          (let [scale-factor-y (/ gradient-length (/ height 2))
                                norm-dist (/ (gpt/distance point from-p)
                                             (* (/ width 2) scale-factor-y))]
                            (when (and norm-dist (mth/finite? norm-dist))
                              (change! {:width norm-dist}))))]

    (when (and gradient
               (= id (:shape-id gradient))
               (not= (:type shape) :text))
      [:& gradient-handler-transformed
       {:editing editing-spot
        :from-p from-p
        :to-p to-p
        :width-p (when (= :radial (:type gradient)) width-p)
        :from-color {:value start-color :opacity start-opacity}
        :to-color {:value end-color :opacity end-opacity}
        :zoom zoom
        :on-change-start on-change-start
        :on-change-finish on-change-finish
        :on-change-width on-change-width}])))
