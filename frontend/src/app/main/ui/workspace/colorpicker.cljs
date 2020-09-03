;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns app.main.ui.workspace.colorpicker
  (:require
   [rumext.alpha :as mf]
   [app.main.store :as st]
   [app.main.ui.colorpicker :as cp]

   [cuerdas.core :as str]
   [app.util.dom :as dom]
   [app.util.color :as uc]
   [app.main.ui.icons :as i]
   [app.common.math :as math]))

;; --- Color Picker Modal

(mf/defc value-selector [{:keys [saturation luminance on-change]}]
  (let [dragging? (mf/use-state false)]
    [:div.value-selector
     {:on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-mouse-move
      (fn [ev]
        (when @dragging?
          (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x y]} (-> ev dom/get-client-position)
                px (/ (- x left) (- right left))
                py (/ (- y top) (- bottom top))

                luminance (* (- 1.0 py) (- 1 (* 0.5 px)))]

            (on-change px luminance))))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* saturation 100) "%")
                            :top (str (* (- 1 (/ luminance (- 1 (* 0.5 saturation))) ) 100) "%")}}]]))

(mf/defc hue-selector [{:keys [hue on-change]}]
  (let [dragging? (mf/use-state false)]
    [:div.hue-selector
     {:on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-mouse-move
      (fn [ev]
        (when @dragging?
          (let [{:keys [left right]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x]} (-> ev dom/get-client-position)
                px (/ (- x left) (- right left))]
            (on-change (* px 360)))))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* (/ hue 360) 100) "%")}}]]))

(mf/defc opacity-selector [{:keys [opacity on-change]}]
  (let [dragging? (mf/use-state false)]
    [:div.opacity-selector
     {:on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-mouse-move
      (fn [ev]
        (when @dragging?
          (let [{:keys [left right]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x]} (-> ev dom/get-client-position)
                px (/ (- x left) (- right left))]
            (on-change px))))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* opacity 100) "%")}}]]))

(defn as-color-state [value opacity]
  (let [[r g b] (uc/hex->rgb (or value "000000"))
        [h s l] (uc/hex->hsl (or value "000000"))]
    {:hex (or value "000000")
     :alpha (or opacity 1)
     :r r
     :g g
     :b b
     :h h
     :s s
     :l l}))

(mf/defc colorpicker
  [{:keys [value opacity]}]
  (let [state (mf/use-state (as-color-state value opacity))
        ref-picker (mf/use-ref)]

    (mf/use-effect (mf/deps value opacity)
                   (fn []
                     (reset! state (as-color-state value opacity))))

    (mf/use-effect (mf/deps state)
                   (fn [] (let [node (mf/ref-val ref-picker)
                                rgb [(:r @state) (:g @state) (:b @state)]
                                hue-rgb (uc/hsl->rgb (:h @state) 1.0 0.5)]
                            (dom/set-css-property node "--color" (str/join ", " rgb))
                            (dom/set-css-property node "--hue" (str/join ", " hue-rgb)))))
    [:div.colorpicker-v2 {:ref ref-picker}
     [:& value-selector {:luminance (:l @state)
                         :saturation (:s @state)
                         :on-change (fn [s l]
                                      (let [hex (uc/hsl->hex (:h @state) s l)
                                            [r g b] (uc/hex->rgb hex)]
                                        (swap! state assoc
                                               :hex hex
                                               :r r :g g :b b
                                               :s s :l l)))}]
     [:div.shade-selector
      [:div.color-bullet]
      [:& hue-selector {:hue (:h @state)
                        :on-change (fn [h]
                                     (let [hex (uc/hsl->hex h (:s @state) (:l @state))
                                           [r g b] (uc/hex->rgb hex)]
                                       (swap! state assoc
                                              :hex hex
                                              :r r :g g :b b
                                              :h h )))}]
      [:& opacity-selector {:opacity (:alpha @state)
                            :on-change (fn [alpha]
                                         (swap! state assoc
                                                :alpha alpha))}]]

     [:div.color-values
      [:input.hex-value {:id "hex-value"
                         :value (:hex @state)
                         :on-change (fn [e]
                                      (let [val (-> e dom/get-target dom/get-value)
                                            val (if (= (first val) \#) val (str \# val))]
                                        (swap! state assoc :hex val)
                                        (when (uc/hex? val)
                                          (let [[r g b] (uc/hex->rgb val)
                                                [h s l] (uc/hex->hsl val)]
                                            (swap! state assoc
                                                   :r r :g g :b b
                                                   :h h :s s :l l)))))}]
      [:input.red-value {:id "red-value"
                         :type "number"
                         :min 0
                         :max 255
                         :value (:r @state)
                         :on-change (fn [e]
                                      (let [val (-> e dom/get-target dom/get-value)
                                            val (if (> val 255) 255 val)
                                            val (if (< val 0) 0 val)]
                                        (swap! state assoc :r val)
                                        (when (not (nil? val))
                                          (let [{:keys [g b]} @state
                                                hex (uc/rgb->hex [val g b])
                                                [h s l] (uc/hex->hsl hex)]
                                            (swap! state assoc
                                                   :hex hex
                                                   :h h :s s :l l)))))}]
      [:input.green-value {:id "green-value"
                           :type "number"
                           :min 0
                           :max 255
                           :value (:g @state)
                           :on-change (fn [e]
                                        (let [val (-> e dom/get-target dom/get-value)
                                              val (if (> val 255) 255 val)
                                              val (if (< val 0) 0 val)]
                                          (swap! state assoc :g val)
                                          (when (not (nil? val))
                                            (let [{:keys [r b]} @state
                                                  hex (uc/rgb->hex [r val b])
                                                  [h s l] (uc/hex->hsl hex)]
                                              (swap! state assoc
                                                     :hex hex
                                                     :h h :s s :l l)))))}]
      [:input.blue-value {:id "blue-value"
                          :type "number"
                          :min 0
                          :max 255
                          :value (:b @state)
                          :on-change (fn [e]
                                       (let [val (-> e dom/get-target dom/get-value)
                                             val (if (> val 255) 255 val)
                                             val (if (< val 0) 0 val)]
                                         (swap! state assoc :b val)
                                         (when (not (nil? val))
                                           (let [{:keys [r g]} @state
                                                 hex (uc/rgb->hex [r g val])
                                                 [h s l] (uc/hex->hsl hex)]
                                             (swap! state assoc
                                                    :hex hex
                                                    :h h :s s :l l)))))}]
      [:input.alpha-value {:id "alpha-value"
                           :type "number"
                           :min 0
                           :step 0.1
                           :max 1
                           :value (math/precision (:alpha @state) 2)
                           :on-change (fn [e]
                                        (let [val (-> e dom/get-target dom/get-value)
                                              val (if (> val 1) 1 val)
                                              val (if (< val 0) 0 val)]
                                          (swap! state assoc :alpha val)))}]
      [:label.hex-label {:for "hex-value"} "HEX"]
      [:label.red-label {:for "red-value"} "R"]
      [:label.green-label {:for "green-value"} "G"]
      [:label.blue-label {:for "blue-value"} "B"]
      [:label.alpha-label {:for "alpha-value"} "A"]]

     [:div.libraries
      [:select
       [:option {:value :recent} "Recent colors"]
       [:option {:value :file} "File library"]
       [:option {:value #uuid "f5d51910-ab23-11ea-ac38-e1abed64181a" } "TAIGA library"]]

      [:div.selected-colors
       [:div.color-bullet.button.plus-button {:style {:background-color "white"}}
        i/plus]

       [:div.color-bullet.button {:style {:background-color "white"}}
        i/palette]

       #_(for [j (range 0 40)]
          [:div.color-bullet {:style {:background-color "#E8E9EA"}}])]]])
  )

(mf/defc colorpicker-modal
  [{:keys [x y default value opacity page on-change disable-opacity] :as props}]
  [:div.modal-overlay.transparent
   [:div.colorpicker-tooltip
    {:style {:left (str (- x 270) "px")
             :top (str (- y 50) "px")}}
    #_[:& cp/colorpicker {:value (or value default)
                        :opacity (or opacity 1)
                        :colors (into-array @cp/most-used-colors)
                        :on-change on-change
                          :disable-opacity disable-opacity}]
    [:& colorpicker {:value (or value default)
                     :opacity (or opacity 1)
                     :colors (into-array @cp/most-used-colors)
                     :on-change on-change
                     :disable-opacity disable-opacity}]
    ]
   ])
