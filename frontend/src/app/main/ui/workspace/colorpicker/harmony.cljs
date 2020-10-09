;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.harmony
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [cuerdas.core :as str]
   [app.common.geom.point :as gpt]
   [app.common.math :as math]
   [app.common.uuid :refer [uuid]]
   [app.util.dom :as dom]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.colors :as dc]
   [app.main.data.modal :as modal]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t]]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector]]))


(defn create-color-wheel
  [canvas-node]
  (let [ctx    (.getContext canvas-node "2d")
        width  (obj/get canvas-node "width")
        height (obj/get canvas-node "height")
        radius (/ width 2)
        cx     (/ width 2)
        cy     (/ width 2)
        step   0.2]

    (.clearRect ctx 0 0 width height)

    (doseq [degrees (range 0 360 step)]
      (let [degrees-rad (math/radians degrees)
            x (* radius (math/cos (- degrees-rad)))
            y (* radius (math/sin (- degrees-rad)))]
        (obj/set! ctx "strokeStyle" (str/format "hsl(%s, 100%, 50%)" degrees))
        (.beginPath ctx)
        (.moveTo ctx cx cy)
        (.lineTo ctx (+ cx x) (+ cy y))
        (.stroke ctx)))

    (let [grd (.createRadialGradient ctx cx cy 0 cx cx radius)]
      (.addColorStop grd 0 "white")
      (.addColorStop grd 1 "rgba(255, 255, 255, 0")
      (obj/set! ctx "fillStyle" grd)

      (.beginPath ctx)
      (.arc ctx cx cy radius 0 (* 2 math/PI) true)
      (.closePath ctx)
      (.fill ctx))))

(defn color->point
  [canvas-side hue saturation]
  (let [hue-rad (math/radians (- hue))
        comp-x (* saturation (math/cos hue-rad))
        comp-y (* saturation (math/sin hue-rad))
        x (+ (/ canvas-side 2) (* comp-x (/ canvas-side 2)))
        y (+ (/ canvas-side 2) (* comp-y (/ canvas-side 2)))]
    (gpt/point x y)))

(mf/defc harmony-selector [{:keys [color on-change]}]
  (let [canvas-ref (mf/use-ref nil)
        {hue :h saturation :s value :v alpha :alpha} color

        canvas-side 152
        pos-current (color->point canvas-side hue saturation)
        pos-complement (color->point canvas-side (mod (+ hue 180) 360) saturation)
        dragging? (mf/use-state false)

        calculate-pos (fn [ev]
                        (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                              {:keys [x y]} (-> ev dom/get-client-position)
                              px (math/clamp (/ (- x left) (- right left)) 0 1)
                              py (math/clamp (/ (- y top) (- bottom top)) 0 1)

                              px (- (* 2 px) 1)
                              py (- (* 2 py) 1)

                              angle (math/degrees (math/atan2 px py))
                              new-hue (math/precision (mod (- angle 90 ) 360) 2)
                              new-saturation (math/clamp (math/distance [px py] [0 0]) 0 1)
                              hex (uc/hsv->hex [new-hue new-saturation value])
                              [r g b] (uc/hex->rgb hex)]
                          (on-change {:hex hex
                                      :r r :g g :b b
                                      :h new-hue
                                      :s new-saturation})))

        on-change-value (fn [new-value]
                          (let [hex (uc/hsv->hex [hue saturation new-value])
                                [r g b] (uc/hex->rgb hex)]
                            (on-change {:hex hex
                                        :r r :g g :b b
                                        :v new-value})))
        on-complement-click (fn [ev]
                              (let [new-hue (mod (+ hue 180) 360)
                                    hex (uc/hsv->hex [new-hue saturation value])
                                    [r g b] (uc/hex->rgb hex)]
                                (on-change {:hex hex
                                            :r r :g g :b b
                                            :h new-hue
                                            :s saturation})))

        on-change-opacity (fn [new-alpha] (on-change {:alpha new-alpha}))]

    (mf/use-effect
     (mf/deps canvas-ref)
     (fn [] (when canvas-ref
              (create-color-wheel (mf/ref-val canvas-ref)))))

    [:div.harmony-selector
     [:div.hue-wheel-wrapper
      [:canvas.hue-wheel
       {:ref canvas-ref
        :width canvas-side
        :height canvas-side
        :on-mouse-down #(reset! dragging? true)
        :on-mouse-up #(reset! dragging? false)
        :on-pointer-down (partial dom/capture-pointer)
        :on-pointer-up (partial dom/release-pointer)
        :on-click calculate-pos
        :on-mouse-move #(when @dragging? (calculate-pos %))}]
      [:div.handler {:style {:pointer-events "none"
                             :left (:x pos-current)
                             :top (:y pos-current)}}]
      [:div.handler.complement {:style {:left (:x pos-complement)
                                        :top (:y pos-complement)
                                        :cursor "pointer"}
                                :on-click on-complement-click}]]
     [:div.handlers-wrapper
      [:& slider-selector {:class "value"
                           :vertical? true
                           :reverse? true
                           :value value
                           :max-value 255
                           :vertical true
                           :on-change on-change-value}]
      [:& slider-selector {:class "opacity"
                           :vertical? true
                           :value alpha
                           :max-value 1
                           :vertical true
                           :on-change on-change-opacity}]]]))
