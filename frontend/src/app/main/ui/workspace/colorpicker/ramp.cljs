;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.ramp
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
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector]]))

(mf/defc value-saturation-selector [{:keys [hue saturation value on-change]}]
  (let [dragging? (mf/use-state false)
        calculate-pos
        (fn [ev]
          (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x y]} (-> ev dom/get-client-position)
                px (math/clamp (/ (- x left) (- right left)) 0 1)
                py (* 255 (- 1 (math/clamp (/ (- y top) (- bottom top)) 0 1)))]
            (on-change px py)))]
    [:div.value-saturation-selector
     {:on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-pointer-down (partial dom/capture-pointer)
      :on-pointer-up (partial dom/release-pointer)
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* 100 saturation) "%")
                            :top (str (* 100 (- 1 (/ value 255))) "%")}}]]))


(mf/defc ramp-selector [{:keys [color disable-opacity on-change]}]
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
       :on-change on-change-value-saturation}]

     [:div.shade-selector
      [:& color-bullet {:color {:color hex
                                :opacity alpha}}]
      [:& slider-selector {:class "hue"
                           :max-value 360
                           :value hue
                           :on-change on-change-hue}]

      (when (not disable-opacity)
        [:& slider-selector {:class "opacity"
                             :max-value 1
                             :value alpha
                             :on-change on-change-opacity}])]]))
