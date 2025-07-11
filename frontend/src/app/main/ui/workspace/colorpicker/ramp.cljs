;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.ramp
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc value-saturation-selector [{:keys [saturation value on-change on-start-drag on-finish-drag]}]
  (let [dragging?* (mf/use-state false)
        dragging? (deref dragging?*)
        calculate-pos
        (fn [ev]
          (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x y]} (-> ev dom/get-client-position)
                px (mth/clamp (/ (- x left) (- right left)) 0 1)
                py (* 255 (- 1 (mth/clamp (/ (- y top) (- bottom top)) 0 1)))]
            (on-change px py)))

        handle-start-drag
        (mf/use-fn
         (mf/deps on-start-drag)
         (fn [event]
           (dom/capture-pointer event)
           (reset! dragging?* true)
           (on-start-drag)))

        handle-stop-drag
        (mf/use-fn
         (mf/deps on-finish-drag)
         (fn [event]
           (dom/release-pointer event)
           (reset! dragging?* false)
           (on-finish-drag)))

        handle-change-pointer-move
        (mf/use-fn
         (mf/deps calculate-pos dragging?)
         (fn [event]
           (when dragging?
             (calculate-pos event))))]

    [:div {:class (stl/css :value-saturation-selector)
           :data-testid "value-saturation-selector"
           :on-pointer-down handle-start-drag
           :on-pointer-up handle-stop-drag
           :on-click calculate-pos
           :on-pointer-move handle-change-pointer-move}
     [:div {:class (stl/css :handler)
            :data-testid "ramp-handler"
            :style {:pointer-events "none"
                    :left (str (* 100 saturation) "%")
                    :top (str (* 100 (- 1 (/ value 255))) "%")}}]]))


(defn- enrich-color-map
  [{:keys [h s v] :as color}]
  (let [h (d/nilv h 0)
        s (d/nilv s 0)
        v (d/nilv v 0)
        hsv [h s v]
        [r g b] (cc/hsv->rgb hsv)]
    (assoc color
           :hex (cc/hsv->hex hsv)
           :h h :s s :v v
           :r r :g g :b b)))

(mf/defc ramp-selector*
  [{:keys [color disable-opacity on-change on-start-drag on-finish-drag]}]
  (let [internal-color*
        (mf/use-state #(enrich-color-map color))

        internal-color
        (deref internal-color*)

        h     (get internal-color :h)
        s     (get internal-color :s)
        v     (get internal-color :v)
        hex   (get internal-color :hex)
        alpha (get internal-color :alpha)

        bullet-color
        (mf/with-memo [hex alpha]
          {:color hex :opacity alpha})

        on-change-value-saturation
        (mf/use-fn
         (mf/deps internal-color on-change)
         (fn [saturation value]
           (let [color (-> internal-color
                           (assoc :s saturation)
                           (assoc :v value)
                           (enrich-color-map))]
             (reset! internal-color* color)
             (on-change color))))

        on-change-hue
        (mf/use-fn
         (mf/deps internal-color on-change)
         (fn [hue]
           (let [color (-> internal-color
                           (assoc :h hue)
                           enrich-color-map)]
             (reset! internal-color* color)
             (on-change color))))

        on-change-opacity
        (mf/use-fn
         (mf/deps internal-color on-change)
         (fn [opacity]
           (let [color (assoc internal-color :alpha opacity)]
             (reset! internal-color* color)
             (on-change color))))]

    (mf/use-effect
     (mf/deps color)
     (fn []
       (reset! internal-color* (enrich-color-map color))))

    [:*
     [:& value-saturation-selector
      {:hue h
       :saturation s
       :value v
       :on-change on-change-value-saturation
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag}]

     [:div {:class (stl/css :shade-selector)
            :style {:--bullet-size "52px"}}
      [:& cb/color-bullet {:color bullet-color
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
