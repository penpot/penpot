;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.slider-selector
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
   [app.util.i18n :as i18n :refer [t]]))

(mf/defc slider-selector
  [{:keys [value class min-value max-value vertical? reverse? on-change]}]
  (let [min-value (or min-value 0)
        max-value (or max-value 1)
        dragging? (mf/use-state false)
        calculate-pos
        (fn [ev]
          (when on-change
            (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                  {:keys [x y]} (-> ev dom/get-client-position)
                  unit-value (if vertical?
                               (math/clamp (/ (- bottom y) (- bottom top)) 0 1)
                               (math/clamp (/ (- x left) (- right left)) 0 1))
                  unit-value (if reverse?
                               (math/abs (- unit-value 1.0))
                               unit-value)
                  value (+ min-value (* unit-value (- max-value min-value)))]
              (on-change (math/precision value 2)))))]

    [:div.slider-selector
     {:class (str (if vertical? "vertical " "") class)
      :on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-pointer-down (partial dom/capture-pointer)
      :on-pointer-up (partial dom/release-pointer)
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}

     (let [value-percent (* (/ (- value min-value)
                               (- max-value min-value)) 100)

           value-percent (if reverse?
                           (math/abs (- value-percent 100))
                           value-percent)
           value-percent-str (str value-percent "%")

           style-common #js {:pointerEvents "none"}
           style-horizontal (obj/merge! #js {:left value-percent-str} style-common)
           style-vertical   (obj/merge! #js {:bottom value-percent-str} style-common)]
       [:div.handler {:style (if vertical? style-vertical style-horizontal)}])]))
