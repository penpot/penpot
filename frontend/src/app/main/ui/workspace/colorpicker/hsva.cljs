;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.hsva
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.color :as cc]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector*]]
   [rumext.v2 :as mf]))

(mf/defc hsva-selector* [{:keys [color disable-opacity mode on-change on-start-drag on-finish-drag]}]
  (let [{hue :h saturation :s value :v alpha :alpha
         r-val :r g-val :g b-val :b} color
        hsl-mode? (= mode :hsl)

        ;; Current HSL derived from RGB — used as the starting point
        ;; for HSL saturation/lightness slider values and for
        ;; recomputing the color when either is dragged.
        [_ hsl-s hsl-l] (if (and r-val g-val b-val)
                          (cc/rgb->hsl [r-val g-val b-val])
                          [0 0 0])

        ;; HSB math — current default behavior.
        handle-change-hsv
        (fn [key]
          (fn [new-value]
            (let [change (hash-map key new-value)
                  {:keys [h s v]} (merge color change)
                  hex (cc/hsv->hex [h s v])
                  [r g b] (cc/hex->rgb hex)]
              (on-change (merge change
                                {:hex hex
                                 :r r :g g :b b})))))

        ;; HSL math — when the user drags the S or L slider in HSL mode,
        ;; we recompute RGB from the updated HSL triple and derive HSV
        ;; for the canonical color representation.
        handle-change-hsl
        (fn [key]
          (fn [new-value]
            (let [new-s   (if (= key :hsl-s) new-value hsl-s)
                  new-l   (if (= key :hsl-l) new-value hsl-l)
                  [r g b] (cc/hsl->rgb [hue new-s new-l])
                  hex     (cc/rgb->hex [r g b])
                  [h s v] (cc/hex->hsv hex)]
              (on-change {:hex hex
                          :h h :s s :v v
                          :r r :g g :b b}))))

        on-change-opacity (fn [new-alpha] (on-change {:alpha new-alpha}))]
    [:div {:class (stl/css :hsva-selector)}
     [:div {:class (stl/css :hsva-row)}
      [:span {:class (stl/css :hsva-selector-label)} "H"]
      [:> slider-selector*
       {:class (stl/css :hsva-bar)
        :type :hue
        :max-value 360
        :value hue
        :on-change (handle-change-hsv :h)
        :on-start-drag on-start-drag
        :on-finish-drag on-finish-drag}]]
     [:div {:class (stl/css :hsva-row)}
      [:span {:class (stl/css :hsva-selector-label)} "S"]
      (if hsl-mode?
        [:> slider-selector*
         {:class (stl/css :hsva-bar)
          :type :hsl-saturation
          :max-value 1
          :value hsl-s
          :on-change (handle-change-hsl :hsl-s)
          :on-start-drag on-start-drag
          :on-finish-drag on-finish-drag}]
        [:> slider-selector*
         {:class (stl/css :hsva-bar)
          :type :saturation
          :max-value 1
          :value saturation
          :on-change (handle-change-hsv :s)
          :on-start-drag on-start-drag
          :on-finish-drag on-finish-drag}])]
     [:div {:class (stl/css :hsva-row)}
      [:span {:class (stl/css :hsva-selector-label)} (if hsl-mode? "L" "B(V)")]
      (if hsl-mode?
        [:> slider-selector*
         {:class (stl/css :hsva-bar)
          :type :lightness
          :max-value 1
          :value hsl-l
          :on-change (handle-change-hsl :hsl-l)
          :on-start-drag on-start-drag
          :on-finish-drag on-finish-drag}]
        [:> slider-selector*
         {:class (stl/css :hsva-bar)
          :type :value
          :max-value 255
          :value value
          :on-change (handle-change-hsv :v)
          :on-start-drag on-start-drag
          :on-finish-drag on-finish-drag}])]
     (when (not disable-opacity)
       [:div {:class (stl/css :hsva-row)}
        [:span {:class (stl/css :hsva-selector-label)} "A"]
        [:> slider-selector*
         {:class (stl/css :hsva-bar)
          :type :opacity
          :max-value 1
          :value alpha
          :on-change on-change-opacity
          :on-start-drag on-start-drag
          :on-finish-drag on-finish-drag}]])]))
