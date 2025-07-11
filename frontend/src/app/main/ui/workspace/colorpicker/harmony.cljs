;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.harmony
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector]]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

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
      (let [degrees-rad (mth/radians degrees)
            x (* radius (mth/cos (- degrees-rad)))
            y (* radius (mth/sin (- degrees-rad)))]
        (obj/set! ctx "strokeStyle" (str/format "hsl(%s, 100%, 50%)" degrees))
        (.beginPath ctx)
        (.moveTo ctx cx cy)
        (.lineTo ctx (+ cx x) (+ cy y))
        (.stroke ctx)))

    (let [grd (.createRadialGradient ctx cx cy 0 cx cx radius)]
      (.addColorStop grd 0 "rgba(255, 255, 255, 1)")
      (.addColorStop grd 1 "rgba(255, 255, 255, 0)")
      (obj/set! ctx "fillStyle" grd)

      (.beginPath ctx)
      (.arc ctx cx cy radius 0 (* 2 mth/PI) true)
      (.closePath ctx)
      (.fill ctx))))

(defn color->point
  [canvas-side hue saturation]
  (let [hue-rad (mth/radians (- hue))
        comp-x (* saturation (mth/cos hue-rad))
        comp-y (* saturation (mth/sin hue-rad))
        x (+ (/ canvas-side 2) (* comp-x (/ canvas-side 2)))
        y (+ (/ canvas-side 2) (* comp-y (/ canvas-side 2)))]
    (gpt/point x y)))

(mf/defc harmony-selector [{:keys [color disable-opacity on-change on-start-drag on-finish-drag]}]
  (let [canvas-ref     (mf/use-ref nil)
        canvas-side    192
        {hue :h saturation :s value :v alpha :alpha} color

        pos-current    (color->point canvas-side hue saturation)
        pos-complement (color->point canvas-side (mod (+ hue 180) 360) saturation)
        dragging?      (mf/use-state false)

        calculate-pos (fn [ev]
                        (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                              {:keys [x y]} (-> ev dom/get-client-position)
                              px (mth/clamp (/ (- x left) (- right left)) 0 1)
                              py (mth/clamp (/ (- y top) (- bottom top)) 0 1)

                              px (- (* 2 px) 1)
                              py (- (* 2 py) 1)

                              angle (mth/degrees (mth/atan2 px py))
                              new-hue (mod (- angle 90) 360)
                              new-saturation (mth/clamp (mth/distance [px py] [0 0]) 0 1)
                              hex (cc/hsv->hex [new-hue new-saturation value])
                              [r g b] (cc/hex->rgb hex)]
                          (on-change {:hex hex
                                      :r r :g g :b b
                                      :h new-hue
                                      :s new-saturation})))

        handle-start-drag
        (mf/use-callback
         (mf/deps on-start-drag)
         (fn [event]
           (dom/capture-pointer event)
           (reset! dragging? true)
           (when on-start-drag
             (on-start-drag))))

        handle-stop-drag
        (mf/use-callback
         (mf/deps on-finish-drag)
         (fn [event]
           (dom/release-pointer event)
           (reset! dragging? false)
           (when on-finish-drag
             (on-finish-drag))))

        on-change-value (fn [new-value]
                          (let [hex (cc/hsv->hex [hue saturation new-value])
                                [r g b] (cc/hex->rgb hex)]
                            (on-change {:hex hex
                                        :r r :g g :b b
                                        :v new-value})))
        on-complement-click (fn [_]
                              (let [new-hue (mod (+ hue 180) 360)
                                    hex (cc/hsv->hex [new-hue saturation value])
                                    [r g b] (cc/hex->rgb hex)]
                                (on-change {:hex hex
                                            :r r :g g :b b
                                            :h new-hue
                                            :s saturation})))

        on-change-opacity (fn [new-alpha] (on-change {:alpha new-alpha}))

        ;; This colors are to display the value slider
        [h1 s1 l1] (cc/hsv->hsl [hue saturation 0])
        [h2 s2 l2] (cc/hsv->hsl [hue saturation 255])]

    (mf/use-effect
     (mf/deps canvas-ref)
     (fn [] (when canvas-ref
              (create-color-wheel (mf/ref-val canvas-ref)))))

    [:div {:class (stl/css :harmony-selector)
           :style {"--hue-from" (dm/str "hsl(" h1 ", " (* s1 100) "%, " (* l1 100) "%)")
                   "--hue-to" (dm/str "hsl(" h2 ", " (* s2 100) "%, " (* l2 100) "%)")}}
     [:div {:class (stl/css :handlers-wrapper)}
      [:& slider-selector {:type :value
                           :vertical? true
                           :reverse? false
                           :value value
                           :max-value 255
                           :vertical true
                           :on-change on-change-value
                           :on-start-drag on-start-drag
                           :on-finish-drag on-finish-drag}]
      (when (not disable-opacity)
        [[:& slider-selector {:type :opacity
                              :vertical? true
                              :value alpha
                              :max-value 1
                              :vertical true
                              :on-change on-change-opacity
                              :on-start-drag on-start-drag
                              :on-finish-drag on-finish-drag}]])]

     [:div {:class (stl/css :hue-wheel-wrapper)}
      [:canvas {:class (stl/css :hue-wheel)
                :ref canvas-ref
                :width canvas-side
                :height canvas-side
                :on-pointer-down handle-start-drag
                :on-pointer-up handle-stop-drag
                :on-lost-pointer-capture handle-stop-drag
                :on-click calculate-pos
                :on-pointer-move #(when @dragging? (calculate-pos %))}]
      [:div {:class (stl/css :handler)
             :style {:pointer-events "none"
                     :left (:x pos-current)
                     :top (:y pos-current)}}]
      [:div {:class (stl/css-case :handler true
                                  :complement true)
             :style {:left (:x pos-complement)
                     :top (:y pos-complement)
                     :cursor "pointer"}
             :on-click on-complement-click}]]]))
