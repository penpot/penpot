;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.hsva
  (:require
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector]]
   [app.util.color :as uc]
   [rumext.v2 :as mf]))

(mf/defc hsva-selector [{:keys [color disable-opacity on-change on-start-drag on-finish-drag]}]
  (let [{hue :h saturation :s value :v alpha :alpha} color
        handle-change-slider (fn [key]
                               (fn [new-value]
                                 (let [change (hash-map key new-value)
                                       {:keys [h s v]} (merge color change)
                                       hex (uc/hsv->hex [h s v])
                                       [r g b] (uc/hex->rgb hex)]
                                   (on-change (merge change
                                                     {:hex hex
                                                      :r r :g g :b b})))))
        on-change-opacity (fn [new-alpha] (on-change {:alpha new-alpha}))]
    [:div.hsva-selector
     [:span.hsva-selector-label "H"]
     [:& slider-selector
      {:class "hue"
       :max-value 360
       :value hue
       :on-change (handle-change-slider :h)
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag}]

     [:span.hsva-selector-label "S"]
     [:& slider-selector
      {:class "saturation"
       :max-value 1
       :value saturation
       :on-change (handle-change-slider :s)
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag}]

     [:span.hsva-selector-label "V"]
     [:& slider-selector
      {:class "value"
       :reverse? true
       :max-value 255
       :value value
       :on-change (handle-change-slider :v)
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag}]

     (when (not disable-opacity)
       [:*
        [:span.hsva-selector-label "A"]
        [:& slider-selector
         {:class "opacity"
          :max-value 1
          :value alpha
          :on-change on-change-opacity
          :on-start-drag on-start-drag
          :on-finish-drag on-finish-drag}]])]))
