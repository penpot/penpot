;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.color-inputs
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(defn parse-hex
  [val]
  (if (= (first val) \#)
    val
    (str \# val)))

(defn value->hsv-value
  [val]
  (* 255 (/ val 100)))

(defn hsv-value->value
  [val]
  (* (/ val 255) 100))

(mf/defc color-inputs* [{:keys [type color disable-opacity mode on-mode-change on-change]}]
  (let [{red :r green :g blue :b
         hue :h saturation :s value :v
         hex :hex alpha :alpha} color

        ;; Sub-model selector for the HSB tab: users can toggle between
        ;; HSB and HSL input display without leaving the tab. State is
        ;; lifted to the colorpicker parent so the slider labels stay
        ;; in sync with the inputs.
        hsb-mode  (or mode :hsb)

        ;; Compute HSL from current RGB (derived; not stored on the color map)
        [_hsl-h hsl-s hsl-l]
        (if (and red green blue)
          (cc/rgb->hsl [red green blue])
          [0 0 0])

        refs {:hex   (mf/use-ref nil)
              :r     (mf/use-ref nil)
              :g     (mf/use-ref nil)
              :b     (mf/use-ref nil)
              :h     (mf/use-ref nil)
              :s     (mf/use-ref nil)
              :v     (mf/use-ref nil)
              :hsl-s (mf/use-ref nil)
              :hsl-l (mf/use-ref nil)
              :alpha (mf/use-ref nil)}

        setup-hex-color
        (fn [hex]
          (let [[r g b] (cc/hex->rgb hex)
                [h s v] (cc/hex->hsv hex)]
            (on-change {:hex hex
                        :h h :s s :v v
                        :r r :g g :b b})))
        on-change-hex
        (fn [e]
          (let [val (-> e dom/get-target-val parse-hex)]
            (when (cc/valid-hex-color? val)
              (setup-hex-color val))))

        on-blur-hex
        (fn [e]
          (let [val (-> e dom/get-target-val)
                ;; FIXME: looks redundant, cc/parse already handles
                ;; hex colors; also it performs the parse-hex twice
                ;; that is completly unnecessary
                val (cond
                      (cc/color-string? val) (cc/parse val)
                      (cc/valid-hex-color? (parse-hex val)) (parse-hex val))]

            (when (some? val)
              (setup-hex-color val))))

        apply-property-change
        (fn [property val]
          (let [val (case property
                      :s (/ val 100)
                      :v (value->hsv-value val)
                      (:hsl-s :hsl-l) (/ val 100)
                      :alpha (/ val 100)
                      val)]
            (cond
              (= property :alpha)
              (on-change {:alpha val})

              (#{:r :g :b} property)
              (let [{:keys [r g b]} (merge color (hash-map property val))
                    hex (cc/rgb->hex [r g b])
                    [h s v] (cc/hex->hsv hex)]
                (on-change {:hex hex
                            :h h :s s :v v
                            :r r :g g :b b}))

              ;; HSL changes: recompute RGB/HSV from the new HSL triple,
              ;; reusing the current hue when only S or L changes.
              (#{:hsl-s :hsl-l} property)
              (let [new-s   (if (= property :hsl-s) val hsl-s)
                    new-l   (if (= property :hsl-l) val hsl-l)
                    [r g b] (cc/hsl->rgb [hue new-s new-l])
                    hex     (cc/rgb->hex [r g b])
                    [h s v] (cc/hex->hsv hex)]
                (on-change {:hex hex
                            :h h :s s :v v
                            :r r :g g :b b}))

              :else
              (let [{:keys [h s v]} (merge color (hash-map property val))
                    hex (cc/hsv->hex [h s v])
                    [r g b] (cc/hex->rgb hex)]
                (on-change {:hex hex
                            :h h :s s :v v
                            :r r :g g :b b})))))

        on-change-property
        (fn [property max-value]
          (fn [e]
            (let [val (-> e dom/get-target-val d/parse-double (mth/clamp 0 max-value))]
              (when (some? val)
                (apply-property-change property val)))))

        on-key-down-step
        (fn [max-value on-step]
          (fn [e]
            (let [up?   (kbd/up-arrow? e)
                  down? (kbd/down-arrow? e)]
              (when (and (or up? down?)
                         (or (kbd/shift? e) (kbd/alt? e)))
                (dom/prevent-default e)
                (when-let [current-value (-> e dom/get-target-val d/parse-double)]
                  (let [step      (cond
                                    (kbd/shift? e) (if up? 10 -10)
                                    (kbd/alt? e)   (if up? 0.1 -0.1))
                        new-value (mth/clamp (+ current-value step) 0 max-value)
                        node      (dom/get-target e)]
                    (dom/set-value! node new-value)
                    (on-step new-value)))))))

        on-key-down-property
        (fn [property max-value]
          (on-key-down-step max-value #(apply-property-change property %)))]


    ;; Updates the inputs values when a property is changed in the parent
    (mf/use-effect
     (mf/deps color type hsb-mode)
     (fn []
       (doseq [ref-key (keys refs)]
         (let [property-val (case ref-key
                              :hsl-s hsl-s
                              :hsl-l hsl-l
                              (get color ref-key))
               property-ref (get refs ref-key)]
           (when (and property-val property-ref)
             (when-let [node (mf/ref-val property-ref)]
               (let [new-val
                     (case ref-key
                       (:s :alpha) (mth/precision (* property-val 100) 2)
                       :v   (mth/precision (hsv-value->value property-val) 2)
                       (:hsl-s :hsl-l) (mth/precision (* property-val 100) 2)
                       property-val)]
                 (dom/set-value! node new-val))))))))

    [:div {:class (stl/css-case :color-values true
                                :disable-opacity disable-opacity)}

     ;; Inline HSB/HSL switcher — only shown on the HSB tab so that
     ;; designers can pick whichever hue-based model matches their
     ;; workflow (HSB matches Figma/Sketch/XD, HSL matches CSS).
     (when (and (not= type :rgb) on-mode-change)
       [:div {:class (stl/css :model-switcher)}
        [:button {:type "button"
                  :class (stl/css-case :model-pill true
                                       :model-pill-active (= hsb-mode :hsb))
                  :on-click #(on-mode-change :hsb)}
         "HSB"]
        [:button {:type "button"
                  :class (stl/css-case :model-pill true
                                       :model-pill-active (= hsb-mode :hsl))
                  :on-click #(on-mode-change :hsl)}
         "HSL"]])

     [:div {:class (stl/css :colors-row)}
      (cond
        (= type :rgb)
        [:*
         [:> input* {:id "red-value"
                     :ref (:r refs)
                     :type "number"
                     :min 0
                     :icon i/character-r
                     :property "Red"
                     :aria-label "Red"
                     :max 255
                     :default-value red
                     :on-change (on-change-property :r 255)
                     :on-key-down (on-key-down-property :r 255)}]
         [:> input* {:id "green-value"
                     :ref (:g refs)
                     :type "number"
                     :min 0
                     :icon i/character-g
                     :property "Green"
                     :aria-label "Green"
                     :max 255
                     :default-value green
                     :on-change (on-change-property :g 255)
                     :on-key-down (on-key-down-property :g 255)}]
         [:> input* {:id "blue-value"
                     :ref (:b refs)
                     :type "number"
                     :min 0
                     :icon i/character-b
                     :property "Blue"
                     :aria-label "Blue"
                     :max 255
                     :default-value blue
                     :on-change (on-change-property :b 255)
                     :on-key-down (on-key-down-property :b 255)}]]

        (= hsb-mode :hsl)
        [:*
         [:> input* {:id "hue-value"
                     :ref (:h refs)
                     :type "number"
                     :min 0
                     :icon i/character-h
                     :property "Hue"
                     :aria-label "Hue"
                     :max 360
                     :default-value hue
                     :on-change (on-change-property :h 360)
                     :on-key-down (on-key-down-property :h 360)}]
         [:> input* {:id "saturation-value"
                     :ref (:s refs)
                     :type "number"
                     :min 0
                     :icon i/character-s
                     :property "Saturation"
                     :aria-label "Saturation"
                     :max 100
                     :step 1
                     :default-value saturation
                     :on-change (on-change-property :s 100)
                     :on-key-down (on-key-down-property :s 100)}]
         [:> input* {:id "lightness-value"
                     :ref (:hsl-l refs)
                     :type "number"
                     :min 0
                     :icon i/character-l
                     :property "Lightness"
                     :aria-label "Lightness"
                     :max 100
                     :step 1
                     :default-value (mth/precision (* hsl-l 100) 2)
                     :on-change (on-change-property :hsl-l 100)
                     :on-key-down (on-key-down-property :hsl-l 100)}]]
        :else
        [:*
         [:> input* {:id "hue-value"
                     :ref (:h refs)
                     :type "number"
                     :min 0
                     :icon i/character-h
                     :property "Hue"
                     :aria-label "Hue"
                     :max 360
                     :default-value hue
                     :on-change (on-change-property :h 360)
                     :on-key-down (on-key-down-property :h 360)}]
         [:> input* {:id "saturation-value"
                     :ref (:s refs)
                     :type "number"
                     :min 0
                     :icon i/character-s
                     :property "Saturation"
                     :max 100
                     :step 1
                     :aria-label "Saturation"
                     :default-value saturation
                     :on-change (on-change-property :s 100)
                     :on-key-down (on-key-down-property :s 100)}]

         [:> input* {:id "brightness-value"
                     :ref (:v refs)
                     :type "number"
                     :min 0
                     :text-icon "B(V)"
                     :property "Brightness"
                     :aria-label "Brightness (Value)"
                     :max 100
                     :step 1
                     :default-value value
                     :on-change (on-change-property :v 100)
                     :on-key-down (on-key-down-property :v 100)}]])]
     [:div {:class (stl/css :hex-alpha-wrapper)}
      [:> input* {:id "hex-value"
                  :class (stl/css :hex-input)
                  :ref (:hex refs)
                  :type "text"
                  :text-icon "HEX"
                  :property "Hex"
                  :aria-label "Hexadecimal color value"
                  :max 7
                  :default-value hex
                  :on-change on-change-hex
                  :on-blur on-blur-hex}]

      (when (not disable-opacity)
        [:> input* {:id "alpha-value"
                    :ref (:alpha refs)
                    :type "number"
                    :class (stl/css :alpha-input)
                    :min 0
                    :icon i/character-a
                    :property "Alpha"
                    :max 100
                    :step 1
                    :aria-label "Alpha"
                    :default-value (if (= alpha :multiple) "" (mth/precision (* alpha 100) 2))
                    :on-change (on-change-property :alpha 100)
                    :on-key-down (on-key-down-property :alpha 100)}])]]))
