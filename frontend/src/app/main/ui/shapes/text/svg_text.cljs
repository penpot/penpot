;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.shapes.text.svg-text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.types.text.japanese-layout :as jl]
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

(defn- px
  "CSS pixel size, or nil for a non-positive size."
  [size]
  (when (pos? size) (dm/str size "px")))

(defn- with-font-features
  [style data]
  (let [font-features (sts/font-feature-settings (:font-features data))]
    (cond-> style
      (some? font-features) (obj/set! "fontFeatureSettings" font-features))))

(defn- annotation-style
  "Style of a ruby, warichu or emphasis line drawn at `font-size` along a
  position-data strip."
  [data font-size vertical? text-orientation fill]
  (with-font-features
    #js {:fontFamily (:font-family data)
         :fontSize (px font-size)
         :fontWeight (:font-weight data)
         :fontStyle (:font-style data)
         :direction (:direction data)
         :writingMode (if vertical? "vertical-rl" "horizontal-tb")
         :textSpacingTrim "normal"
         :textOrientation text-orientation
         :textAutospace "normal"
         :whiteSpace "pre"
         :fill fill}
    data))

(defn- strip-text-props
  "Props of the `text` element drawing a position-data strip's base text."
  [shape data index fill browser-props]
  (let [style (with-font-features
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
                data)
        key   (dm/str "text-" (:id shape) "-" index)]
    (if (= "vertical-rl" (:writing-mode data))
      ;; Vertical strip: glyphs run down the column; x is the column's
      ;; central axis and y the strip top (stored y is the strip bottom).
      #js {:key key
           :x (+ (:x data) (/ (:width data) 2))
           :y (- (:y data) (:height data))
           :textLength (:height data)
           :lengthAdjust "spacingAndGlyphs"
           :style (-> style
                      (obj/set! "writingMode" "vertical-rl")
                      (obj/set! "textOrientation" (or (:text-orientation data) "mixed"))
                      (obj/set! "textAutospace" "normal")
                      (obj/set! "fill" fill))}
      (cond-> #js {:key key
                   :x (if (= "rtl" (:direction data)) (+ (:x data) (:width data)) (:x data))
                   :y (:y data)
                   :dominantBaseline "ideographic"
                   :textLength (:width data)
                   :lengthAdjust "spacingAndGlyphs"
                   :style (obj/set! style "fill" fill)}
        (some? browser-props)
        (obj/merge! browser-props)))))

(defn- warichu-lines
  "Warichu: two half-size sub-lines within one inline strip. Vertical
  reading order is right then left; horizontal is top then bottom."
  [shape data index fill]
  (let [vertical?   (= "vertical-rl" (:writing-mode data))
        font-size   (* (js/parseFloat (:font-size data)) jl/warichu-font-scale)
        font-size   (if (js/isNaN font-size) 0 font-size)
        style       (annotation-style data font-size vertical? (when vertical? "upright") fill)
        text        (:text data)
        split-index (warichu-split-index text)
        lines       [(subs text 0 split-index) (subs text split-index)]
        top         (- (:y data) (:height data))
        centre      (+ (:x data) (/ (:width data) 2))
        quarter     (/ font-size 2)]
    (mf/html
     [:g {:key (dm/str "warichu-" (:id shape) "-" index)}
      (if vertical?
        [:*
         [:> :text {:x (+ centre quarter) :y top :style style} (first lines)]
         [:> :text {:x (- centre quarter) :y top :style style} (second lines)]]
        [:*
         (for [[line-index line] (d/enumerate lines)]
           [:> :text {:key line-index
                      :x (:x data)
                      :y (+ top (* line-index (/ (:height data) 2)))
                      :dominantBaseline "hanging"
                      :textLength (:width data)
                      :lengthAdjust "spacingAndGlyphs"
                      :style style}
            line])])])))

(defn- ruby-text-props
  "Props of the `text` element drawing a strip's ruby annotation beside it
  (vertical) or above/below it (horizontal), per its side and alignment."
  [shape data index ruby-font-size style]
  (let [key       (dm/str "ruby-" (:id shape) "-" index)
        ruby-side (:ruby-side data "over")]
    (if (= "vertical-rl" (:writing-mode data))
      #js {:key key
           :x (if (= "under" ruby-side)
                (- (:x data) (/ ruby-font-size 2))
                (+ (:x data) (:width data) (/ ruby-font-size 2)))
           :y (- (:y data) (:height data))
           :textLength (:height data)
           :lengthAdjust "spacingAndGlyphs"
           :style style}
      (let [rtl?       (= "rtl" (:direction data))
            ruby-align (:ruby-align data "space-around")
            fit?       (or (= "none" (:ruby-overhang data "auto"))
                           (= "space-around" ruby-align))]
        (cond-> #js {:key key
                     :x (if (= "center" ruby-align)
                          (+ (:x data) (/ (:width data) 2))
                          (if rtl? (+ (:x data) (:width data)) (:x data)))
                     :y (if (= "under" ruby-side)
                          (:y data)
                          (- (:y data) (:height data)))
                     :dominantBaseline (if (= "under" ruby-side) "hanging" "text-after-edge")
                     :style style}
          (= "center" ruby-align)
          (obj/set! "textAnchor" "middle")

          (= "start" ruby-align)
          (obj/set! "textAnchor" (if rtl? "end" "start"))

          fit?
          (-> (obj/set! "textLength" (:width data))
              (obj/set! "lengthAdjust" "spacingAndGlyphs"))

          (= "space-between" ruby-align)
          (-> (obj/set! "textLength" (:width data))
              (obj/set! "lengthAdjust" "spacing")))))))

(defn- emphasis-text-props
  "Props of the `text` element drawing a strip's emphasis marks: right of
  vertical text or above horizontal text, outside a stacked ruby layer."
  [shape data index font-size ruby-offset style]
  (let [key (dm/str "emphasis-" (:id shape) "-" index)]
    (if (= "vertical-rl" (:writing-mode data))
      #js {:key key
           :x (+ (:x data) (:width data) (/ font-size 2) ruby-offset)
           :y (- (:y data) (:height data))
           :textLength (:height data)
           :lengthAdjust "spacing"
           :style style}
      #js {:key key
           :x (if (= "rtl" (:direction data)) (+ (:x data) (:width data)) (:x data))
           :y (- (:y data) (:height data) ruby-offset)
           :dominantBaseline "text-after-edge"
           :textLength (:width data)
           :lengthAdjust "spacing"
           :style style})))

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
        (let [vertical?        (= "vertical-rl" (:writing-mode data))
              ruby             (jl/visible-ruby data)
              emphasis-mark    (get emphasis-mark-chars (:text-emphasis data))
              font-size        (js/parseFloat (:font-size data))
              scaled-size      (fn [scale] (if (js/isNaN font-size) 0 (* font-size scale)))
              ruby-font-size   (scaled-size (jl/ruby-font-scale (:ruby-size data)))
              ;; Annotations take the fills of this strip's own render id.
              annotation-fill  (str "url(#fill-" index "-" render-id "-" index ")")

              browser-props
              (when (and (not vertical?) (cf/check-browser? :safari))
                #js {:dominantBaseline "hanging"
                     :dy "0.2em"
                     :y (- (:y data) (:height data))})

              text-props (strip-text-props shape data index
                                           (str "url(#fill-" index "-" render-id ")")
                                           browser-props)
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
            (if (jl/warichu-text? data)
              (warichu-lines shape data index annotation-fill)
              [:> :text text-props (:text data)])]
           (when (some? ruby)
             [:> :text (ruby-text-props shape data index ruby-font-size
                                        (annotation-style data ruby-font-size vertical?
                                                          "upright" annotation-fill))
              ruby])
           ;; Emphasis marks (圏点): one half-size mark per eligible base
           ;; character.
           (when (some? emphasis-mark)
             (let [ruby-layer?  (and (= "auto" (:annotation-clearance data))
                                     (not= "under" (:ruby-side data "over"))
                                     (or (some? ruby) (:annotation-has-ruby data)))
                   mark-size    (scaled-size jl/emphasis-font-scale)
                   style        (annotation-style data mark-size vertical?
                                                  (when vertical? "upright")
                                                  annotation-fill)]
               [:> :text (emphasis-text-props shape data index mark-size
                                              (if ruby-layer? ruby-font-size 0)
                                              style)
                (emphasis-marks-text (:text data) emphasis-mark)]))]))]]))
