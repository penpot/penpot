;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.text.svg-text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.config :as cf]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.main.ui.shapes.fills :as fills]
   [app.main.ui.shapes.gradients :as grad]
   [app.main.ui.shapes.text.styles :as sts]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def fill-attrs [:fill-color :fill-color-gradient :fill-opacity])

(defn- ruby-font-scale
  [ruby-size]
  (case ruby-size
    "third" (/ 1 3)
    "quarter" 0.25
    0.5))

(def ^:private emphasis-font-scale 0.5)

(def ^:private warichu-font-scale 0.5)

;; Kinsoku classes for the warichu sub-line split (same characters the
;; renderer's kinsoku module suppresses at line boundaries).
(def ^:private warichu-forbidden-at-start
  (str "、。，．）」』］】〕〉》’”！？；：ー"
       "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ"
       "々ゝゞヽヾ・"))

(def ^:private warichu-forbidden-at-end "（「『［【〔〈《‘“")

(defn- warichu-split-index
  "Safe JavaScript string index where a warichu run splits into its two
  sub-lines. The split is chosen in Unicode code-point space, then translated
  back to a UTF-16 boundary for `subs`: the
  balanced midpoint (first sub-line longer), nudged forward then backward
  so the second sub-line does not start with a line-start-prohibited
  character and the first does not end with a line-end-prohibited one.
  Mirrors the renderer's `warichu_split_chars`."
  [text]
  (let [characters (vec (js/Array.from text))
        n      (count characters)
        mid    (js/Math.ceil (/ n 2))
        valid? (fn [split]
                 (and (>= split 1)
                      (< split n)
                      (not (.includes warichu-forbidden-at-start (nth characters split)))
                      (not (.includes warichu-forbidden-at-end (nth characters (dec split))))))
        split  (if (valid? mid)
                 mid
                 (or (->> (range 1 n)
                          (some (fn [distance]
                                  (cond
                                    (valid? (+ mid distance)) (+ mid distance)
                                    (and (> mid distance) (valid? (- mid distance))) (- mid distance)))))
                     mid))]
    (->> (take split characters)
         (reduce (fn [index character] (+ index (.-length character))) 0))))

;; CSS `text-emphasis-style` character mapping (same glyphs the canvas
;; renderer shapes for each mark style).
(def ^:private emphasis-mark-chars
  {"filled-dot"     "•"
   "open-dot"       "◦"
   "filled-circle"  "●"
   "open-circle"    "○"
   "filled-sesame"  "﹅"
   "open-sesame"    "﹆"})

(def ^:private emphasis-prohibited-chars
  "、。，．「」『』（）［］【】〔〕〈〉《》‘’“”")

(defn- emphasis-character?
  [character]
  (and (not (re-matches #"\s" character))
       (not (.includes emphasis-prohibited-chars character))))

(defn- emphasis-marks-text
  "One mark per eligible Unicode base character. Spaces replace whitespace
  and Japanese punctuation that does not normally carry emphasis so marks
  stay aligned with the base slots."
  [text mark]
  (->> (js/Array.from text)
       (map (fn [character]
              (if (emphasis-character? character) mark " ")))
       (apply str)))

(defn- add-font-features!
  [style font-features]
  (cond-> style
    (and (string? font-features)
         (not= "none" font-features)
         (pos? (alength font-features)))
    (obj/set! "fontFeatureSettings" (dm/str "\"" font-features "\""))))

(defn set-white-fill
  [shape]
  (let [update-color
        (fn [data]
          (-> data
              (dissoc :fill-color :fill-opacity :fill-color-gradient)
              (assoc :fills [{:fill-color "#FFFFFF" :fill-opacity 1}])))]
    (-> shape
        (d/update-when :position-data #(mapv update-color %))
        (assoc :stroke-color "#FFFFFF" :stroke-opacity 1))))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]

  (let [render-id (mf/use-ctx muc/render-id)
        shape (obj/get props "shape")
        shape (cond-> shape (:is-mask? shape) set-white-fill)

        {:keys [x y width height position-data]} shape

        transform (gsh/transform-str shape)

        ;; These position attributes are not really necessary but they are convenient for for the export
        group-props (-> #js {:transform transform
                             :className "text-container"
                             :x x
                             :y y
                             :width width
                             :height height}
                        (attrs/add-fill-props! shape render-id)
                        (attrs/add-border-props! shape))
        get-gradient-id
        (fn [index]
          (str render-id "-" (:id shape) "-" index))]

    [:*
     ;; Definition of gradients for partial elements
     (when (d/seek :fill-color-gradient position-data)
       [:defs
        (for [[index data] (d/enumerate position-data)]
          (when (some? (:fill-color-gradient data))
            (let [id (dm/str "fill-color-gradient-" (get-gradient-id index))]
              [:& grad/gradient {:id id
                                 :key id
                                 :attr :fill-color-gradient
                                 :shape data}])))])

     [:> :g group-props
      (for [[index data] (d/enumerate position-data)]
        (let [rtl? (= "rtl" (:direction data))
              vertical? (= "vertical-rl" (:writing-mode data))
              ruby (:ruby data)
              ruby? (and (string? ruby) (seq ruby))
              ruby-side (:ruby-side data "over")
              ruby-align (:ruby-align data "space-around")
              ruby-overhang (:ruby-overhang data "auto")
              auto-clearance? (= "auto" (:annotation-clearance data))
              has-ruby-layer? (and (not= "under" ruby-side)
                                   (or ruby? (:annotation-has-ruby data)))
              emphasis-mark (get emphasis-mark-chars (:text-emphasis data))
              emphasis? (some? emphasis-mark)
              warichu? (and (= "warichu" (:warichu data))
                            (string? (:text data))
                            (>= (count (:text data)) 2))
              font-features (:font-features data)

              browser-props
              (cond
                (and (not vertical?) (cf/check-browser? :safari))
                #js {:dominantBaseline "hanging"
                     :dy "0.2em"
                     :y (- (:y data) (:height data))})

              base-style (add-font-features!
                          #js {:fontFamily (:font-family data)
                               :fontSize (:font-size data)
                               :fontWeight (:font-weight data)
                               :textTransform (:text-transform data)
                               :textDecoration (:text-decoration data)
                               :textCombineUpright (sts/css-text-combine-upright
                                                    (:text-combine-upright data))
                               :letterSpacing (:letter-spacing data)
                               :fontStyle (:font-style data)
                               :direction (:direction data)
                               :textSpacingTrim "normal"
                               :whiteSpace "pre"}
                          font-features)

              font-size (js/parseFloat (:font-size data))
              ruby-font-size-num (if (js/isNaN font-size)
                                   0
                                   (* font-size (ruby-font-scale (:ruby-size data))))
              ruby-font-size (when (pos? ruby-font-size-num)
                               (dm/str ruby-font-size-num "px"))
              ruby-style (add-font-features!
                          #js {:fontFamily (:font-family data)
                               :fontSize ruby-font-size
                               :fontWeight (:font-weight data)
                               :fontStyle (:font-style data)
                               :direction (:direction data)
                               :writingMode (if vertical? "vertical-rl" "horizontal-tb")
                               :textSpacingTrim "normal"
                               :textOrientation "upright"
                               :textAutospace "normal"
                               :whiteSpace "pre"
                               :fill (str "url(#fill-" index "-" render-id "-" index ")")}
                          font-features)

              emphasis-font-size-num (if (js/isNaN font-size)
                                       0
                                       (* font-size emphasis-font-scale))

              warichu-font-size-num (if (js/isNaN font-size)
                                      0
                                      (* font-size warichu-font-scale))
              warichu-style (add-font-features!
                             #js {:fontFamily (:font-family data)
                                  :fontSize (when (pos? warichu-font-size-num)
                                              (dm/str warichu-font-size-num "px"))
                                  :fontWeight (:font-weight data)
                                  :fontStyle (:font-style data)
                                  :direction (:direction data)
                                  :writingMode (if vertical? "vertical-rl" "horizontal-tb")
                                  :textSpacingTrim "normal"
                                  :textOrientation (when vertical? "upright")
                                  :textAutospace "normal"
                                  :whiteSpace "pre"
                                  :fill (str "url(#fill-" index "-" render-id "-" index ")")}
                             font-features)
              emphasis-style (add-font-features!
                              #js {:fontFamily (:font-family data)
                                   :fontSize (when (pos? emphasis-font-size-num)
                                               (dm/str emphasis-font-size-num "px"))
                                   :fontWeight (:font-weight data)
                                   :fontStyle (:font-style data)
                                   :direction (:direction data)
                                   :writingMode (if vertical? "vertical-rl" "horizontal-tb")
                                   :textSpacingTrim "normal"
                                   :textOrientation (when vertical? "upright")
                                   :textAutospace "normal"
                                   :whiteSpace "pre"
                                   :fill (str "url(#fill-" index "-" render-id "-" index ")")}
                              font-features)

              props (if vertical?
                      ;; Vertical strip: glyphs run down the column; x is
                      ;; the column's central axis and y the strip top
                      ;; (stored y is the strip bottom).
                      #js {:key (dm/str "text-" (:id shape) "-" index)
                           :x (+ (:x data) (/ (:width data) 2))
                           :y (- (:y data) (:height data))
                           :textLength (:height data)
                           :lengthAdjust "spacingAndGlyphs"
                           :style (-> base-style
                                      (obj/set! "writingMode" "vertical-rl")
                                      (obj/set! "textSpacingTrim" "normal")
                                      (obj/set! "textOrientation" (or (:text-orientation data) "mixed"))
                                      (obj/set! "textAutospace" "normal")
                                      (obj/set! "fill" (str "url(#fill-" index "-" render-id ")")))}
                      (-> #js {:key (dm/str "text-" (:id shape) "-" index)
                               :x (if rtl? (+ (:x data) (:width data)) (:x data))
                               :y (:y data)
                               :dominantBaseline "ideographic"
                               :textLength (:width data)
                               :lengthAdjust "spacingAndGlyphs"
                               :style (obj/set! base-style "fill" (str "url(#fill-" index "-" render-id ")"))}
                          (cond-> browser-props
                            (obj/merge! browser-props))))
              shape (-> shape
                        (assoc :fills (:fills data))
                        ;; The text elements have the shadow and blur already applied in the
                        ;; group parent.
                        (dissoc :shadow :blur))

              ;; Need to create new render-id per text-block
              render-id (dm/str render-id "-" index)]

          [:& (mf/provider muc/render-id) {:key index :value render-id}
           ;; Text fills definition. Need to be defined per-text block
           [:defs
            [:& fills/fills          {:shape shape :render-id render-id}]]

           [:& shape-custom-strokes {:shape shape :position index :render-id render-id}
            (if warichu?
              ;; Warichu: two half-size sub-lines within one inline strip.
              ;; Vertical reading order is right then left; horizontal is
              ;; top then bottom.
              (let [text        (:text data)
                    split-index (warichu-split-index text)
                    centre      (+ (:x data) (/ (:width data) 2))
                    quarter     (/ warichu-font-size-num 2)
                    top         (- (:y data) (:height data))]
                [:g {:key (dm/str "warichu-" (:id shape) "-" index)}
                 (if vertical?
                   [:*
                    [:> :text {:x (+ centre quarter)
                               :y top
                               :style warichu-style}
                     (subs text 0 split-index)]
                    [:> :text {:x (- centre quarter)
                               :y top
                               :style warichu-style}
                     (subs text split-index)]]
                   [:*
                    [:> :text {:x (:x data)
                               :y top
                               :dominantBaseline "hanging"
                               :textLength (:width data)
                               :lengthAdjust "spacingAndGlyphs"
                               :style warichu-style}
                     (subs text 0 split-index)]
                    [:> :text {:x (:x data)
                               :y (+ top (/ (:height data) 2))
                               :dominantBaseline "hanging"
                               :textLength (:width data)
                               :lengthAdjust "spacingAndGlyphs"
                               :style warichu-style}
                     (subs text split-index)]])])
              [:> :text props (:text data)])]
           (when ruby?
             [:> :text (if vertical?
                         #js {:key (dm/str "ruby-" (:id shape) "-" index)
                              :x (if (= "under" ruby-side)
                                   (- (:x data) (/ ruby-font-size-num 2))
                                   (+ (:x data) (:width data) (/ ruby-font-size-num 2)))
                              :y (- (:y data) (:height data))
                              :textLength (:height data)
                              :lengthAdjust "spacingAndGlyphs"
                              :style ruby-style}
                         (let [start-x (if rtl? (+ (:x data) (:width data)) (:x data))
                               center-x (+ (:x data) (/ (:width data) 2))
                               constrain? (= "none" ruby-overhang)]
                           (cond-> #js {:key (dm/str "ruby-" (:id shape) "-" index)
                                        :x (if (= "center" ruby-align) center-x start-x)
                                        :y (if (= "under" ruby-side)
                                             (:y data)
                                             (- (:y data) (:height data)))
                                        :dominantBaseline (if (= "under" ruby-side)
                                                            "hanging"
                                                            "text-after-edge")
                                        :style ruby-style}
                             (= "center" ruby-align)
                             (obj/set! "textAnchor" "middle")

                             (= "start" ruby-align)
                             (obj/set! "textAnchor" (if rtl? "end" "start"))

                             (or constrain? (= "space-around" ruby-align))
                             (obj/set! "textLength" (:width data))

                             (or constrain? (= "space-around" ruby-align))
                             (obj/set! "lengthAdjust" "spacingAndGlyphs")

                             (= "space-between" ruby-align)
                             (obj/set! "textLength" (:width data))

                             (= "space-between" ruby-align)
                             (obj/set! "lengthAdjust" "spacing"))))
              ruby])
           ;; Emphasis marks (圏点): one half-size mark per eligible base
           ;; character, right of vertical text or above horizontal text.
           (when emphasis?
             [:> :text (if vertical?
                         #js {:key (dm/str "emphasis-" (:id shape) "-" index)
                              :x (+ (:x data)
                                    (:width data)
                                    (/ emphasis-font-size-num 2)
                                    (if (and auto-clearance? has-ruby-layer?)
                                      ruby-font-size-num
                                      0))
                              :y (- (:y data) (:height data))
                              :textLength (:height data)
                              :lengthAdjust "spacing"
                              :style emphasis-style}
                         #js {:key (dm/str "emphasis-" (:id shape) "-" index)
                              :x (if rtl? (+ (:x data) (:width data)) (:x data))
                              :y (- (:y data)
                                    (:height data)
                                    (if (and auto-clearance? has-ruby-layer?)
                                      ruby-font-size-num
                                      0))
                              :dominantBaseline "text-after-edge"
                              :textLength (:width data)
                              :lengthAdjust "spacing"
                              :style emphasis-style})
              (emphasis-marks-text (:text data) emphasis-mark)])]))]]))
