;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.hsva
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

(mf/defc hsva-selector [{:keys [color on-change]}]
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
      {:class "hue" :max-value 360 :value hue :on-change (handle-change-slider :h)}]

     [:span.hsva-selector-label "S"]
     [:& slider-selector
      {:class "saturation" :max-value 1 :value saturation :on-change (handle-change-slider :s)}]

     [:span.hsva-selector-label "V"]
     [:& slider-selector
      {:class "value" :reverse? true :max-value 255 :value value :on-change (handle-change-slider :v)}]

     [:span.hsva-selector-label "A"]
     [:& slider-selector
      {:class "opacity" :max-value 1 :value alpha :on-change on-change-opacity}]]))
