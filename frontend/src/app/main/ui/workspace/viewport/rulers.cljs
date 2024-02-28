;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.rulers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as hooks]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def rulers-pos 15)
(def rulers-size 4)
(def rulers-width 1)
(def ruler-area-size 22)
(def ruler-area-half-size (/ ruler-area-size 2))
(def rulers-background "var(--panel-background-color)")
(def selection-area-color "var(--color-accent-tertiary)")
(def selection-area-opacity 0.3)
(def over-number-size 100)
(def over-number-opacity 0.8)
(def over-number-percent 0.75)

(def font-size 12)
(def font-family "worksans")
(def font-color "var(--layer-row-foreground-color)")
(def canvas-border-radius 12)

;; ----------------
;;   RULERS
;; ----------------

(defn- calculate-step-size
  [zoom]
  (cond
    (< 0 zoom 0.008) 10000
    (< 0.008 zoom 0.015) 5000
    (< 0.015 zoom 0.04) 2500
    (< 0.04 zoom 0.07) 1000
    (< 0.07 zoom 0.2) 500
    (< 0.2 zoom 0.5) 250
    (< 0.5 zoom 1) 100
    (<= 1 zoom 2) 50
    (< 2 zoom 4) 25
    (< 4 zoom 6) 10
    (< 6 zoom 15) 5
    (< 15 zoom 25) 2
    (< 25 zoom) 1
    :else 1))

(defn get-clip-area
  [vbox zoom-inverse axis]
  (if (= axis :x)
    (let [x      (+ (:x vbox) (* 25 zoom-inverse))
          y      (:y vbox)
          width  (- (:width vbox) (* 21 zoom-inverse))
          height (* 25 zoom-inverse)]
      {:x x :y y :width width :height height})

    (let [x      (:x vbox)
          y      (+ (:y vbox) (* 25 zoom-inverse))
          width  (* 25 zoom-inverse)
          height (- (:height vbox) (* 21 zoom-inverse))]
      {:x x :y y :width width :height height})))

(defn get-background-area
  [vbox zoom-inverse axis]
  (if (= axis :x)
    (let [x      (:x vbox)
          y      (:y vbox)
          width  (:width vbox)
          height (* ruler-area-size zoom-inverse)]
      {:x x :y y :width width :height height})

    (let [x      (:x vbox)
          y      (+ (:y vbox) (* ruler-area-size zoom-inverse))
          width  (* ruler-area-size zoom-inverse)
          height (- (:height vbox) (* 21 zoom-inverse))]
      {:x x :y y :width width :height height})))

(defn get-ruler-params
  [vbox axis]
  (if (= axis :x)
    (let [start (:x vbox)
          end   (+ start (:width vbox))]
      {:start start :end end})

    (let [start (:y vbox)
          end   (+ start (:height vbox))]
      {:start start :end end})))

(defn get-ruler-axis
  [val vbox zoom-inverse axis]
  (let [rulers-pos (* rulers-pos zoom-inverse)
        rulers-size (* rulers-size zoom-inverse)]
    (if (= axis :x)
      {:text-x val
       :text-y (+ (:y vbox) (- rulers-pos (* 4 zoom-inverse)))
       :line-x1 val
       :line-y1 (+ (:y vbox) rulers-pos (* 2 zoom-inverse))
       :line-x2 val
       :line-y2 (+ (:y vbox) rulers-pos (* 2 zoom-inverse) rulers-size)}

      {:text-x (+ (:x vbox) (- rulers-pos (* 4 zoom-inverse)))
       :text-y val
       :line-x1 (+ (:x vbox) rulers-pos (* 2 zoom-inverse))
       :line-y1 val
       :line-x2 (+ (:x vbox) rulers-pos (* 2 zoom-inverse) rulers-size)
       :line-y2 val})))

(defn rulers-outside-path
  "Path data for the viewport outside"
  [x1 y1 x2 y2]
  (dm/str
   "M" x1 "," y1
   "L" x2 "," y1
   "L" x2 "," y2
   "L" x1 "," y2
   "Z"))

(defn rulers-inside-path
  "Calculates the path for the inside of the viewport frame"
  [x1 y1 x2 y2 br bw]
  (dm/str
   "M" (+ x1 bw) "," (+ y1 bw br)
   "Q" (+ x1 bw) "," (+ y1 bw) "," (+ x1 bw br) "," (+ y1 bw)

   "L" (- x2 br) "," (+ y1 bw)
   "Q" x2 "," (+ y1 bw) "," x2 "," (+ y1 bw br)

   "L" x2 "," (- y2 br)
   "Q" x2 "," y2 "," (- x2 br) "," y2

   "L" (+ x1 bw br) "," y2
   "Q" (+ x1 bw) "," y2 "," (+ x1 bw) "," (- y2 br)

   "Z"))

(mf/defc rulers-text
  "Draws the text for the rulers in a specific axis"
  [{:keys [vbox step offset axis zoom-inverse]}]
  (let [clip-id (str "clip-ruler-" (d/name axis))
        {:keys [start end]} (get-ruler-params vbox axis)
        minv (max start -100000)
        minv (* (mth/ceil (/ minv step)) step)
        maxv (min end 100000)
        maxv (* (mth/floor (/ maxv step)) step)

        ;; These extra operations ensure that we are selecting a frame its initial location is rendered in the ruler
        minv (+ minv (mod offset step))
        maxv (+ maxv (mod offset step))

        rulers-width (* rulers-width zoom-inverse)]

    [:g.rulers {:clipPath (str "url(#" clip-id ")")}
     [:defs
      [:clipPath {:id clip-id}
       (let [{:keys [x y width height]} (get-clip-area vbox zoom-inverse axis)]
         [:rect {:x x :y y :width width :height height}])]]

     (for [step-val (range minv (inc maxv) step)]
       (let [{:keys [text-x text-y line-x1 line-y1 line-x2 line-y2]}
             (get-ruler-axis step-val vbox zoom-inverse axis)]
         [:* {:key (dm/str "text-" (d/name axis) "-" step-val)}
          [:text {:x text-x
                  :y text-y
                  :text-anchor "middle"
                  :dominant-baseline "middle"
                  :transform (when (= axis :y) (str "rotate(-90 " text-x "," text-y ")"))
                  :style {:font-size (* font-size zoom-inverse)
                          :font-family font-family
                          :fill font-color}}
           ;; If the guide is associated to a frame we show the position relative to the frame
           (fmt/format-number (- step-val offset))]

          [:line {:key (str "line-" (d/name axis) "-"  step-val)
                  :x1 line-x1
                  :y1 line-y1
                  :x2 line-x2
                  :y2 line-y2
                  :style {:stroke font-color
                          :stroke-width rulers-width}}]]))]))

(mf/defc viewport-frame
  [{:keys [show-rulers? zoom zoom-inverse vbox offset-x offset-y]}]

  (let [{:keys [width height] x1 :x y1 :y} vbox
        x2 (+ x1 width)
        y2 (+ y1 height)
        bw (if show-rulers? (* ruler-area-size zoom-inverse) 0)
        br (/ canvas-border-radius zoom)
        bs (* 4 zoom-inverse)]
    [:*
     [:g.viewport-frame-background
      ;; This goes behind because if it goes in front the background bleeds through
      [:path {:d (rulers-inside-path x1 y1 x2 y2 br bw)
              :fill "none"
              :stroke-width bs
              :stroke "var(--panel-border-color)"}]

      [:path {:d (dm/str (rulers-outside-path x1 y1 x2 y2)
                         (rulers-inside-path x1 y1 x2 y2 br bw))
              :fill-rule "evenodd"
              :fill rulers-background}]]

     (when show-rulers?
       (let [step (calculate-step-size zoom)]
         [:g.viewport-frame-rulers
          [:& rulers-text {:vbox vbox :offset offset-x :step step :zoom-inverse zoom-inverse :axis :x}]
          [:& rulers-text {:vbox vbox :offset offset-y :step step :zoom-inverse zoom-inverse :axis :y}]]))]))

(mf/defc selection-area
  [{:keys [vbox zoom-inverse selection-rect offset-x offset-y]}]
  ;; When using the format-number callls we consider if the guide is associated to a frame and we show the position relative to it with the offset
  [:g.selection-area
   [:defs
    [:linearGradient {:id "selection-gradient-start"}
     [:stop {:offset "0%" :stop-color rulers-background :stop-opacity 0}]
     [:stop {:offset "40%" :stop-color rulers-background :stop-opacity 1}]
     [:stop {:offset "100%" :stop-color rulers-background :stop-opacity 1}]]

    [:linearGradient {:id "selection-gradient-end"}
     [:stop {:offset "0%" :stop-color rulers-background :stop-opacity 1}]
     [:stop {:offset "60%" :stop-color rulers-background :stop-opacity 1}]
     [:stop {:offset "100%" :stop-color rulers-background :stop-opacity 0}]]]
   [:g
    [:rect {:x (- (:x selection-rect) (* (* over-number-size over-number-percent) zoom-inverse))
            :y (:y vbox)
            :width (* over-number-size zoom-inverse)
            :height (* ruler-area-size zoom-inverse)
            :fill "url('#selection-gradient-start')"}]

    [:rect {:x (- (:x2 selection-rect) (* over-number-size (- 1 over-number-percent)))
            :y (:y vbox)
            :width (* over-number-size zoom-inverse)
            :height (* ruler-area-size zoom-inverse)
            :fill "url('#selection-gradient-end')"}]

    [:rect {:x (:x selection-rect)
            :y (:y vbox)
            :width (:width selection-rect)
            :height (* ruler-area-size zoom-inverse)
            :style {:fill selection-area-color
                    :fill-opacity selection-area-opacity}}]

    [:text {:x (- (:x1 selection-rect) (* 4 zoom-inverse))
            :y (+ (:y vbox) (* 10.6 zoom-inverse))
            :text-anchor "end"
            :dominant-baseline "middle"
            :style {:font-size (* font-size zoom-inverse)
                    :font-family font-family
                    :fill selection-area-color}}
     (fmt/format-number (- (:x1 selection-rect) offset-x))]

    [:text {:x (+ (:x2 selection-rect) (* 4 zoom-inverse))
            :y (+ (:y vbox) (* 10.6 zoom-inverse))
            :text-anchor "start"
            :dominant-baseline "middle"
            :style {:font-size (* font-size zoom-inverse)
                    :font-family font-family
                    :fill selection-area-color}}
     (fmt/format-number (- (:x2 selection-rect) offset-x))]]

   (let [center-x (+ (:x vbox) (* ruler-area-half-size zoom-inverse))
         center-y (- (+ (:y selection-rect) (/ (:height selection-rect) 2)) (* ruler-area-half-size zoom-inverse))]

     [:g {:transform (str "rotate(-90 " center-x "," center-y ")")}
      [:rect {:x (- center-x (/ (:height selection-rect) 2) (* ruler-area-half-size zoom-inverse))
              :y (- center-y (* ruler-area-half-size zoom-inverse))
              :width (:height selection-rect)
              :height (* ruler-area-size zoom-inverse)
              :style {:fill selection-area-color
                      :fill-opacity selection-area-opacity}}]

      [:rect {:x (- center-x (/ (:height selection-rect) 2) (* ruler-area-half-size zoom-inverse) (* over-number-size zoom-inverse))
              :y (- center-y (* ruler-area-half-size zoom-inverse))
              :width (* over-number-size zoom-inverse)
              :height (* ruler-area-size zoom-inverse)
              :style {:fill rulers-background
                      :fill-opacity over-number-opacity}}]

      [:rect {:x (+ (- center-x (/ (:height selection-rect) 2) (* ruler-area-half-size zoom-inverse)) (:height selection-rect))
              :y (- center-y (* ruler-area-half-size zoom-inverse))
              :width (* over-number-size zoom-inverse)
              :height (* ruler-area-size zoom-inverse)
              :style {:fill rulers-background
                      :fill-opacity over-number-opacity}}]

      [:text {:x (- center-x (/ (:height selection-rect) 2) (* 15 zoom-inverse))
              :y center-y
              :text-anchor "end"
              :dominant-baseline "middle"
              :style {:font-size (* font-size zoom-inverse)
                      :font-family font-family
                      :fill selection-area-color}}
       (fmt/format-number (- (:y2 selection-rect) offset-y))]

      [:text {:x (+ center-x (/ (:height selection-rect) 2))
              :y center-y
              :text-anchor "start"
              :dominant-baseline "middle"
              :style {:font-size (* font-size zoom-inverse)
                      :font-family font-family
                      :fill selection-area-color}}
       (fmt/format-number (- (:y1 selection-rect) offset-y))]])])

(mf/defc rulers
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["zoom" "vbox" "selected-shapes" "show-rulers?"]))]}
  [props]
  (let [zoom            (obj/get props "zoom")
        zoom-inverse    (obj/get props "zoom-inverse")
        vbox            (obj/get props "vbox")
        offset-x        (obj/get props "offset-x")
        offset-y        (obj/get props "offset-y")
        selected-shapes (-> (obj/get props "selected-shapes")
                            (hooks/use-equal-memo))
        show-rulers?    (obj/get props "show-rulers?")

        selection-rect
        (mf/use-memo
         (mf/deps selected-shapes)
         #(when (d/not-empty? selected-shapes)
            (gsh/shapes->rect selected-shapes)))]

    (when (some? vbox)
      [:g.viewport-frame {:pointer-events "none"}
       [:& viewport-frame
        {:show-rulers? show-rulers?
         :zoom zoom
         :zoom-inverse zoom-inverse
         :vbox vbox
         :offset-x offset-x
         :offset-y offset-y}]

       (when (and show-rulers? (some? selection-rect))
         [:& selection-area
          {:zoom zoom
           :zoom-inverse zoom-inverse
           :vbox vbox
           :selection-rect selection-rect
           :offset-x offset-x
           :offset-y offset-y}])])))
