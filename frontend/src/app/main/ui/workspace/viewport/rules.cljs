;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.rules
  (:require
   [app.common.colors :as colors]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as hooks]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def rules-pos 15)
(def rules-size 4)
(def rules-width 1)
(def rule-area-size 22)
(def rule-area-half-size (/ rule-area-size 2))
(def rules-background "var(--color-gray-50)")
(def selection-area-color "var(--color-primary)")
(def selection-area-opacity 0.3)
(def over-number-size 50)
(def over-number-opacity 0.7)

(def font-size 12)
(def font-family "worksans")

;; ----------------
;;   RULES
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
          height (* rule-area-size zoom-inverse)]
      {:x x :y y :width width :height height})

    (let [x      (:x vbox)
          y      (+ (:y vbox) (* rule-area-size zoom-inverse))
          width  (* rule-area-size zoom-inverse)
          height (- (:height vbox) (* 21 zoom-inverse))]
      {:x x :y y :width width :height height})))

(defn get-rule-params
  [vbox axis]
  (if (= axis :x)
    (let [start (:x vbox)
          end   (+ start (:width vbox))]
      {:start start :end end})

    (let [start (:y vbox)
          end   (+ start (:height vbox))]
      {:start start :end end})))

(defn get-rule-axis
  [val vbox zoom-inverse axis]
  (let [rules-pos (* rules-pos zoom-inverse)
        rules-size (* rules-size zoom-inverse)]
    (if (= axis :x)
      {:text-x val
       :text-y (+ (:y vbox) (- rules-pos (* 4 zoom-inverse)))
       :line-x1 val
       :line-y1 (+ (:y vbox) rules-pos (* 2 zoom-inverse))
       :line-x2 val
       :line-y2 (+ (:y vbox) rules-pos (* 2 zoom-inverse) rules-size)}

      {:text-x (+ (:x vbox) (- rules-pos (* 4 zoom-inverse)))
       :text-y val
       :line-x1 (+ (:x vbox) rules-pos (* 2 zoom-inverse))
       :line-y1 val
       :line-x2 (+ (:x vbox) rules-pos (* 2 zoom-inverse) rules-size)
       :line-y2 val})))

(mf/defc rules-axis
  [{:keys [zoom zoom-inverse vbox axis offset]}]
  (let [rules-width (* rules-width zoom-inverse)
        step (calculate-step-size zoom)
        clip-id (str "clip-rule-" (d/name axis))]

    [:*
     (let [{:keys [x y width height]} (get-background-area vbox zoom-inverse axis)]
       [:rect {:x x :y y :width width :height height :style {:fill rules-background}}])

     [:g.rules {:clipPath (str "url(#" clip-id ")")}

      [:defs
       [:clipPath {:id clip-id}
        (let [{:keys [x y width height]} (get-clip-area vbox zoom-inverse axis)]
          [:rect {:x x :y y :width width :height height}])]]

      (let [{:keys [start end]} (get-rule-params vbox axis)
            minv (max start -100000)
            minv (* (mth/ceil (/ minv step)) step)
            maxv (min end 100000)
            maxv (* (mth/floor (/ maxv step)) step)
            
            ;; These extra operations ensure that we are selecting a frame its initial location is rendered in the rule
            minv (+ minv (mod offset step))
            maxv (+ maxv (mod offset step))]
        
        (for [step-val (range minv (inc maxv) step)]
          (let [{:keys [text-x text-y line-x1 line-y1 line-x2 line-y2]}
                (get-rule-axis step-val vbox zoom-inverse axis)]
            [:* {:key (dm/str "text-" (d/name axis) "-" step-val)}
             [:text {:x text-x
                     :y text-y
                     :text-anchor "middle"
                     :dominant-baseline "middle"
                     :transform (when (= axis :y) (str "rotate(-90 " text-x "," text-y ")"))
                     :style {:font-size (* font-size zoom-inverse)
                             :font-family font-family
                             :fill colors/gray-30}}
              ;; If the guide is associated to a frame we show the position relative to the frame
              (fmt/format-number (- step-val offset))]

             [:line {:key (str "line-" (d/name axis) "-"  step-val)
                     :x1 line-x1
                     :y1 line-y1
                     :x2 line-x2
                     :y2 line-y2
                     :style {:stroke colors/gray-30
                             :stroke-width rules-width}}]])))]]))

(mf/defc selection-area
  [{:keys [vbox zoom-inverse selection-rect offset-x offset-y]}]
  ;; When using the format-number callls we consider if the guide is associated to a frame and we show the position relative to it with the offset
  [:g.selection-area
   [:g
    [:rect {:x (:x selection-rect)
            :y (:y vbox)
            :width (:width selection-rect)
            :height (* rule-area-size zoom-inverse)
            :style {:fill selection-area-color
                    :fill-opacity selection-area-opacity}}]

    [:rect {:x (- (:x selection-rect) (* over-number-size zoom-inverse))
            :y (:y vbox)
            :width (* over-number-size zoom-inverse)
            :height (* rule-area-size zoom-inverse)
            :style {:fill rules-background
                    :fill-opacity over-number-opacity}}]

    [:text {:x (- (:x1 selection-rect) (* 4 zoom-inverse))
            :y (+ (:y vbox) (* 12 zoom-inverse))
            :text-anchor "end"
            :dominant-baseline "middle"
            :style {:font-size (* font-size zoom-inverse)
                    :font-family font-family
                    :fill selection-area-color}}
     (fmt/format-number (- (:x1 selection-rect) offset-x))]

    [:rect {:x (:x2 selection-rect)
            :y (:y vbox)
            :width (* over-number-size zoom-inverse)
            :height (* rule-area-size zoom-inverse)
            :style {:fill rules-background
                    :fill-opacity over-number-opacity}}]

    [:text {:x (+ (:x2 selection-rect) (* 4 zoom-inverse))
            :y (+ (:y vbox) (* 12 zoom-inverse))
            :text-anchor "start"
            :dominant-baseline "middle"
            :style {:font-size (* font-size zoom-inverse)
                    :font-family font-family
                    :fill selection-area-color}}
     (fmt/format-number (- (:x2 selection-rect) offset-x))]]

   (let [center-x (+ (:x vbox) (* rule-area-half-size zoom-inverse))
         center-y (- (+ (:y selection-rect) (/ (:height selection-rect) 2)) (* rule-area-half-size zoom-inverse))]

     [:g {:transform (str "rotate(-90 " center-x "," center-y ")")}
      [:rect {:x (- center-x (/ (:height selection-rect) 2) (* rule-area-half-size zoom-inverse))
              :y (- center-y (* rule-area-half-size zoom-inverse))
              :width (:height selection-rect)
              :height (* rule-area-size zoom-inverse)
              :style {:fill selection-area-color
                      :fill-opacity selection-area-opacity}}]

      [:rect {:x (- center-x (/ (:height selection-rect) 2) (* rule-area-half-size zoom-inverse) (* over-number-size zoom-inverse))
              :y (- center-y (* rule-area-half-size zoom-inverse))
              :width (* over-number-size zoom-inverse)
              :height (* rule-area-size zoom-inverse)
              :style {:fill rules-background
                      :fill-opacity over-number-opacity}}]

      [:rect {:x (+ (- center-x (/ (:height selection-rect) 2) (* rule-area-half-size zoom-inverse) ) (:height selection-rect))
              :y (- center-y (* rule-area-half-size zoom-inverse))
              :width (* over-number-size zoom-inverse)
              :height (* rule-area-size zoom-inverse)
              :style {:fill rules-background
                      :fill-opacity over-number-opacity}}]

      [:text {:x (- center-x (/ (:height selection-rect) 2) (* 15 zoom-inverse))
              :y center-y
              :text-anchor "end"
              :dominant-baseline "middle"
              :style {:font-size (* font-size zoom-inverse)
                      :font-family font-family
                      :fill selection-area-color}}
       (fmt/format-number (- (:y2 selection-rect) offset-y))]

      [:text {:x (+ center-x (/ (:height selection-rect) 2) )
              :y center-y
              :text-anchor "start"
              :dominant-baseline "middle"
              :style {:font-size (* font-size zoom-inverse)
                      :font-family font-family
                      :fill selection-area-color}}
       (fmt/format-number (- (:y1 selection-rect) offset-y))]])])

(mf/defc rules
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["zoom" "vbox" "selected-shapes"]))]}
  [props]
  (let [zoom            (obj/get props "zoom")
        zoom-inverse    (obj/get props "zoom-inverse")
        vbox            (obj/get props "vbox")
        offset-x        (obj/get props "offset-x")
        offset-y        (obj/get props "offset-y")
        selected-shapes (-> (obj/get props "selected-shapes")
                            (hooks/use-equal-memo))

        selection-rect
        (mf/use-memo
         (mf/deps selected-shapes)
         #(when (d/not-empty? selected-shapes)
            (gsh/shapes->rect selected-shapes)))]

    (when (some? vbox)
      [:g.rules {:pointer-events "none"}
       [:& rules-axis {:zoom zoom :zoom-inverse zoom-inverse :vbox vbox :axis :x :offset offset-x}]
       [:& rules-axis {:zoom zoom :zoom-inverse zoom-inverse :vbox vbox :axis :y :offset offset-y}]

       (when (some? selection-rect)
         [:& selection-area {:zoom zoom
                             :zoom-inverse zoom-inverse
                             :vbox vbox
                             :selection-rect selection-rect
                             :offset-x offset-x
                             :offset-y offset-y}])])))
