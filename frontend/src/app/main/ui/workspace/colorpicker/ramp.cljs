;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.ramp
  (:require
   [app.common.math :as mth]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector]]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc value-saturation-selector [{:keys [saturation value on-change on-start-drag on-finish-drag]}]
  (let [dragging? (mf/use-state false)
        calculate-pos
        (fn [ev]
          (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x y]} (-> ev dom/get-client-position)
                px (mth/clamp (/ (- x left) (- right left)) 0 1)
                py (* 255 (- 1 (mth/clamp (/ (- y top) (- bottom top)) 0 1)))]
            (on-change px py)))

        handle-start-drag
        (mf/use-callback
         (mf/deps on-start-drag)
         (fn [event]
           (dom/capture-pointer event)
           (reset! dragging? true)
           (on-start-drag)))

        handle-stop-drag
        (mf/use-callback
         (mf/deps on-finish-drag)
         (fn [event]
           (dom/release-pointer event)
           (reset! dragging? false)
           (on-finish-drag)))
        ]
    [:div.value-saturation-selector
     {:on-pointer-down handle-start-drag
      :on-pointer-up handle-stop-drag
      :on-lost-pointer-capture handle-stop-drag
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* 100 saturation) "%")
                            :top (str (* 100 (- 1 (/ value 255))) "%")}}]]))


(mf/defc ramp-selector [{:keys [color disable-opacity on-change on-start-drag on-finish-drag]}]
  (let [{hex :hex
         hue :h saturation :s value :v alpha :alpha} color

        on-change-value-saturation
        (fn [new-saturation new-value]
          (let [hex (uc/hsv->hex [hue new-saturation new-value])
                [r g b] (uc/hex->rgb hex)]
            (on-change {:hex hex
                        :r r :g g :b b
                        :s new-saturation
                        :v new-value})))

        on-change-hue
        (fn [new-hue]
          (let [hex (uc/hsv->hex [new-hue saturation value])
                [r g b] (uc/hex->rgb hex)]
            (on-change {:hex hex
                        :r r :g g :b b
                        :h new-hue} )))

        on-change-opacity
        (fn [new-opacity]
          (on-change {:alpha new-opacity} ))]
    [:*
     [:& value-saturation-selector
      {:hue hue
       :saturation saturation
       :value value
       :on-change on-change-value-saturation
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag}]

     [:div.shade-selector
      [:& color-bullet {:color {:color hex
                                :opacity alpha}}]
      [:& slider-selector {:class "hue"
                           :max-value 360
                           :value hue
                           :on-change on-change-hue
                           :on-start-drag on-start-drag
                           :on-finish-drag on-finish-drag}]

      (when (not disable-opacity)
        [:& slider-selector {:class "opacity"
                             :max-value 1
                             :value alpha
                             :on-change on-change-opacity
                             :on-start-drag on-start-drag
                             :on-finish-drag on-finish-drag}])]]))
