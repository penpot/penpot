;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.ramp
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as cc]
   [app.common.math :as mth]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector]]
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
           (on-finish-drag)))]
    [:div {:class (stl/css :value-saturation-selector)
           :on-pointer-down handle-start-drag
           :on-pointer-up handle-stop-drag
           :on-lost-pointer-capture handle-stop-drag
           :on-click calculate-pos
           :on-pointer-move #(when @dragging? (calculate-pos %))}
     [:div {:class (stl/css :handler)
            :style {:pointer-events "none"
                    :left (str (* 100 saturation) "%")
                    :top (str (* 100 (- 1 (/ value 255))) "%")}}]]))


(defn enrich-color-map [{:keys [h s v] :as color}]
  (let [[r g b] (cc/hsv->rgb [h s v])]
    (assoc color
           :hex (cc/hsv->hex [h s v])
           :r r :g g :b b)))

(mf/defc ramp-selector [{:keys [color disable-opacity on-change on-start-drag on-finish-drag]}]
  (let [internal-color (mf/use-state)
        {:keys [h s v hex alpha]} @internal-color
        on-change-value-saturation
        (fn [new-saturation new-value]
          (let [new-color (-> @internal-color
                              enrich-color-map
                              (assoc :s new-saturation
                                     :v new-value))]
            (reset! internal-color new-color)
            (on-change new-color)))

        on-change-hue
        (fn [new-hue]
          (let [new-color (-> @internal-color
                              enrich-color-map
                              (assoc :h new-hue))]
            (reset! internal-color new-color)
            (on-change new-color)))

        on-change-opacity
        (fn [new-opacity]
          (let [new-color (-> @internal-color
                              enrich-color-map
                              (assoc :alpha new-opacity))]
            (reset! internal-color new-color)
            (on-change new-color)))]

    (mf/use-effect
     (fn []
       (reset! internal-color (enrich-color-map color))))
    [:*
     [:& value-saturation-selector
      {:hue h
       :saturation s
       :value v
       :on-change on-change-value-saturation
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag}]

     [:div {:class (stl/css :shade-selector)
            :style #js {"--bullet-size" "52px"}}
      [:& cb/color-bullet {:color {:color hex
                                   :opacity alpha}
                           :area true}]
      [:div {:class (stl/css :sliders-wrapper)}
       [:& slider-selector {:type :hue
                            :max-value 360
                            :value h
                            :on-change on-change-hue
                            :on-start-drag on-start-drag
                            :on-finish-drag on-finish-drag}]

       (when (not disable-opacity)
         [:& slider-selector {:type :opacity
                              :max-value 1
                              :value alpha
                              :on-change on-change-opacity
                              :on-start-drag on-start-drag
                              :on-finish-drag on-finish-drag}])]]]))
