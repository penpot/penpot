;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.hsva
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.color :as cc]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector]]
   [rumext.v2 :as mf]))

(mf/defc hsva-selector [{:keys [color disable-opacity on-change on-start-drag on-finish-drag]}]
  (let [{hue :h saturation :s value :v alpha :alpha} color
        handle-change-slider (fn [key]
                               (fn [new-value]
                                 (let [change (hash-map key new-value)
                                       {:keys [h s v]} (merge color change)
                                       hex (cc/hsv->hex [h s v])
                                       [r g b] (cc/hex->rgb hex)]
                                   (on-change (merge change
                                                     {:hex hex
                                                      :r r :g g :b b})))))
        on-change-opacity (fn [new-alpha] (on-change {:alpha new-alpha}))]
    [:div {:class (stl/css :hsva-selector)}
     [:div {:class (stl/css :hsva-row)}
      [:span {:class (stl/css :hsva-selector-label)} "H"]
      [:& slider-selector
       {:class (stl/css :hsva-bar)
        :type :hue
        :max-value 360
        :value hue
        :on-change (handle-change-slider :h)
        :on-start-drag on-start-drag
        :on-finish-drag on-finish-drag}]]
     [:div {:class (stl/css :hsva-row)}
      [:span {:class (stl/css :hsva-selector-label)} "S"]
      [:& slider-selector
       {:class (stl/css :hsva-bar)
        :type :saturation
        :max-value 1
        :value saturation
        :on-change (handle-change-slider :s)
        :on-start-drag on-start-drag
        :on-finish-drag on-finish-drag}]]
     [:div {:class (stl/css :hsva-row)}
      [:span {:class (stl/css :hsva-selector-label)} "V"]
      [:& slider-selector
       {:class (stl/css :hsva-bar)
        :type :value
        :reverse? false
        :max-value 255
        :value value
        :on-change (handle-change-slider :v)
        :on-start-drag on-start-drag
        :on-finish-drag on-finish-drag}]]
     (when (not disable-opacity)
       [:div {:class (stl/css :hsva-row)}
        [:span {:class (stl/css :hsva-selector-label)} "A"]
        [:& slider-selector
         {:class (stl/css :hsva-bar)
          :type :opacity
          :max-value 1
          :value alpha
          :on-change on-change-opacity
          :on-start-drag on-start-drag
          :on-finish-drag on-finish-drag}]])]))
