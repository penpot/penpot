;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.slider-selector
  (:require
   [app.common.math :as mth]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc slider-selector
  [{:keys [value class min-value max-value vertical? reverse? on-change on-start-drag on-finish-drag]}]
  (let [min-value (or min-value 0)
        max-value (or max-value 1)
        dragging? (mf/use-state false)

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

        calculate-pos
        (fn [ev]
          (when on-change
            (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                  {:keys [x y]} (-> ev dom/get-client-position)
                  unit-value (if vertical?
                               (mth/clamp (/ (- bottom y) (- bottom top)) 0 1)
                               (mth/clamp (/ (- x left) (- right left)) 0 1))
                  unit-value (if reverse?
                               (mth/abs (- unit-value 1.0))
                               unit-value)
                  value (+ min-value (* unit-value (- max-value min-value)))]
              (on-change value))))]

    [:div.slider-selector
     {:class (str (if vertical? "vertical " "") class)
      :on-pointer-down handle-start-drag
      :on-pointer-up handle-stop-drag
      :on-lost-pointer-capture handle-stop-drag
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}

     (let [value-percent (* (/ (- value min-value)
                               (- max-value min-value)) 100)

           value-percent (if reverse?
                           (mth/abs (- value-percent 100))
                           value-percent)
           value-percent-str (str value-percent "%")

           style-common #js {:pointerEvents "none"}
           style-horizontal (obj/merge! #js {:left value-percent-str} style-common)
           style-vertical   (obj/merge! #js {:bottom value-percent-str} style-common)]
       [:div.handler {:style (if vertical? style-vertical style-horizontal)}])]))
