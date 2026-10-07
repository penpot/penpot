;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.shapes.text.styles
  (:require
   [app.common.data :as d]
   [app.common.transit :as transit]
   [app.common.types.color :as cc]
   [app.common.types.text :as txt]
   [app.common.types.text.japanese-layout :as jl]
   [app.main.fonts :as fonts]
   [app.main.ui.formats :as fmt]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn generate-root-styles
  "Root styles. `code?` leaves out the box size, which generated code sets
   elsewhere. The root writing mode makes paragraph blocks stack
   right-to-left."
  ([props node]
   (generate-root-styles props node false))
  ([{:keys [width height]} node code?]
   (let [valign (:vertical-align node "top")
         writing-mode (jl/content-writing-mode node)
         base   #js {:height (when-not code? (fmt/format-pixels height))
                     :width  (when-not code? (fmt/format-pixels width))
                     :display "flex"
                     :whiteSpace "break-spaces"}]
     (cond-> base
       (= valign "top")     (obj/set! "alignItems" "flex-start")
       (= valign "center")  (obj/set! "alignItems" "center")
       (= valign "bottom")  (obj/set! "alignItems" "flex-end")
       (some? writing-mode) (obj/set! "writingMode" writing-mode)))))

(defn generate-paragraph-set-styles
  [{:keys [grow-type] :as shape}]
  ;; This element will control the auto-width/auto-height size for the
  ;; shape. The properties try to adjust to the shape and "overflow" if
  ;; the shape is not big enough.
  ;; We `inherit` the property `justify-content` so it's set by the root where
  ;; the property it's known.
  ;; `inline-flex` is similar to flex but `overflows` outside the bounds of the
  ;; parent
  (let [auto-width?  (= grow-type :auto-width)]
    #js {:display "inline-flex"
         :flexDirection "column"
         :justifyContent "inherit"
         :minWidth (when-not auto-width? "100%")
         :marginRight "1px"
         :verticalAlign "top"}))

(defn generate-paragraph-styles
  [_shape data]
  (let [line-height (:line-height data)
        line-height
        (if (and (some? line-height) (not= "" line-height))
          line-height
          (:line-height txt/default-typography))

        text-align       (:text-align data "start")
        writing-mode     (:writing-mode data)
        text-orientation (:text-orientation data)
        base             #js {;; Fix a problem when exporting HTML
                              :fontSize 0
                              :lineHeight line-height
                              :margin 0}]

    (cond-> base
      (some? line-height)       (obj/set! "lineHeight" line-height)
      (some? line-height)       (obj/set! "--paragraph-line-height" (str line-height))
      (some? text-align)        (obj/set! "textAlign" text-align)
      (some? writing-mode)      (obj/set! "writingMode" writing-mode)
      (some? writing-mode)      (obj/set! "textSpacingTrim" "normal")
      (= writing-mode "vertical-rl") (obj/set! "textAutospace" "normal")
      (some? text-orientation)  (obj/set! "textOrientation" text-orientation))))

(defn css-text-combine-upright
  "CSS value for a persisted text-combine-upright, or nil for the digits
   variants: browsers do not support CSS `digits <n>`, so renderers wrap each
   digit run (`jl/digit-combine-segments`) in an `all` span instead."
  [value]
  (when-not (jl/digit-combine? value)
    value))

;; Style of a combined digit run inside a `digits` span.
(def tcy-run-style #js {:textCombineUpright "all"})

(defn text-children
  "Text of a node, with each combined digit run of a `digits` tate-chu-yoko
   in its own `all` span (class `tcy` for generated code)."
  [text node]
  (if-let [segments (jl/digit-combine-segments text (:text-combine-upright node))]
    (into-array
     (for [[index [run combine?]] (d/enumerate segments)]
       (if combine?
         (mf/html [:span.tcy {:key index :style tcy-run-style} run])
         run)))
    text))

(defn- set-value?
  "True for a stored style value other than empty or \"none\"."
  [value]
  (and (string? value) (pos? (alength value)) (not= "none" value)))

(defn font-feature-settings
  "CSS `font-feature-settings` for a persisted font-features value, or nil."
  [font-features]
  (when (set-value? font-features)
    (str/format "\"%s\"" font-features)))

(defn- annotation-line-height
  "CSS line height that adds the room of automatic annotation clearance (the
   ruby at its size plus the emphasis marks) to the paragraph line height,
   which paragraphs expose as `--paragraph-line-height`. A span's own line
   height wins when larger, as in the renderer. Nil under `none`."
  [data]
  (when (= "auto" (:annotation-clearance data))
    (let [room      (+ (if (jl/visible-ruby data) (jl/ruby-font-scale (:ruby-size data)) 0)
                       (if (set-value? (:text-emphasis data)) jl/emphasis-font-scale 0))
          paragraph (str "var(--paragraph-line-height, "
                         (:line-height txt/default-typography) ")")
          own       (js/parseFloat (:line-height data))
          base      (if (js/isNaN own)
                      paragraph
                      (str "max(" paragraph ", " own ")"))]
      (when (pos? room)
        (str "calc(" base " + " (fmt/format-number room) ")")))))

(defn- add-japanese-text-styles!
  [style data]
  (let [text-combine-upright (:text-combine-upright data)
        text-emphasis        (:text-emphasis data)
        font-features        (font-feature-settings (:font-features data))
        line-height          (annotation-line-height data)
        font-size            (:font-size data)]
    (cond-> style
      (some? (css-text-combine-upright text-combine-upright))
      (obj/set! "textCombineUpright" (css-text-combine-upright text-combine-upright))

      ;; Stored kebab values ("filled-dot") become the CSS "<fill> <shape>"
      ;; pair ("filled dot").
      (set-value? text-emphasis)
      (obj/set! "textEmphasis" (str/replace text-emphasis "-" " "))

      (some? font-features)
      (obj/set! "fontFeatureSettings" font-features)

      (some? line-height)
      (obj/set! "lineHeight" line-height)

      ;; Warichu (割注): a half-size inline-block as wide as half the
      ;; characters, so the browser wraps it into two sub-lines.
      (jl/warichu-text? data)
      (-> (obj/set! "display" "inline-block")
          (obj/set! "fontSize" (if (and (string? font-size) (pos? (alength font-size)))
                                 (str (* (js/parseFloat font-size) jl/warichu-font-scale) "px")
                                 "50%"))
          (obj/set! "lineHeight" "1")
          (obj/set! "inlineSize"
                    (str (js/Math.ceil (/ (alength (js/Array.from (:text data))) 2)) "em"))))))

(defn generate-text-styles
  ([shape data]
   (generate-text-styles shape data nil))

  ([{:keys [grow-type] :as shape} data {:keys [show-text?] :or {show-text? true}}]
   (let [letter-spacing  (:letter-spacing data 0)
         text-decoration (:text-decoration data)
         text-transform  (:text-transform data)

         font-id         (or (:font-id data)
                             (:font-id txt/default-typography))

         font-variant-id (:font-variant-id data)

         font-size       (:font-size data)

         fill-color      (or (-> data :fills first :fill-color) (:fill-color data))
         fill-opacity    (or (-> data :fills first :fill-opacity) (:fill-opacity data))
         fill-gradient   (or (-> data :fills first :fill-color-gradient) (:fill-color-gradient data))

         [r g b a]       (cc/hex->rgba fill-color fill-opacity)
         text-color      (when (and (some? fill-color) (some? fill-opacity))
                           (str/format "rgba(%s, %s, %s, %s)" r g b a))

         gradient?       (some? fill-gradient)

         text-color      (if gradient?
                           (uc/color->background {:gradient fill-gradient})
                           text-color)

         fontsdb         (deref fonts/fontsdb)

         base            #js {:textDecoration text-decoration
                              :textTransform text-transform
                              :fontSize font-size
                              :color (if (and show-text? (not gradient?)) text-color "transparent")
                              :background (when (and show-text? gradient?) text-color)
                              :caretColor (if (and (not gradient?) text-color) text-color "black")
                              :overflowWrap "initial"
                              :lineBreak "auto"
                              :whiteSpace "break-spaces"
                              :textRendering "geometricPrecision"}
         base            (cond-> base
                           (= (:line-height data) "0")
                           (-> (obj/set! "display" "inline-block")
                               (obj/set! "verticalAlign" "top")))
         fills
         (cond
           ;; DEPRECATED: still here for backward compatibility with
           ;; old penpot files that still has a single color.
           (or (some? (:fill-color data))
               (some? (:fill-opacity data))
               (some? (:fill-color-gradient data)))
           [(d/without-nils (select-keys data [:fill-color :fill-opacity :fill-color-gradient
                                               :fill-color-ref-id :fill-color-ref-file]))]

           (nil? (:fills data))
           [{:fill-color "#000000" :fill-opacity 1}]

           :else
           (:fills data))

         font (some->> font-id (get fontsdb))

         [font-family font-style font-weight]
         (when (some? font)
           (let [font-variant (d/seek #(= font-variant-id (:id %)) (:variants font))]
             [(str/quote (or (:family font) (:font-family data)))
              (or (:style font-variant) (:font-style data))
              (or (:weight font-variant) (:font-weight data))]))

         base (obj/set! base "--font-id" font-id)]

     (cond-> base
       (some? fills)
       (obj/set! "--fills" (transit/encode-str fills))

       (and (string? letter-spacing) (pos? (alength letter-spacing)))
       (obj/set! "letterSpacing" (str letter-spacing "px"))

       (and (string? font-size) (pos? (alength font-size)))
       (obj/set! "fontSize" (str font-size "px"))

       (some? font)
       (-> (obj/set! "fontFamily" font-family)
           (obj/set! "fontStyle" font-style)
           (obj/set! "fontWeight" font-weight))

       (= grow-type :auto-width)
       (obj/set! "whiteSpace" "pre")

       :always
       (add-japanese-text-styles! data)))))

(defn- ruby-font-size
  "Pixel size of a span's ruby text. Paragraphs set font-size 0, so a
   relative size would resolve to nothing."
  [data]
  (let [font-size (:font-size data)
        font-size (js/parseFloat (if (and (string? font-size) (pos? (alength font-size)))
                                   font-size
                                   (:font-size txt/default-typography)))]
    (str (* font-size (jl/ruby-font-scale (:ruby-size data))) "px")))

(defn generate-ruby-styles
  [shape data]
  (-> (generate-text-styles shape data)
      (obj/set! "fontSize" (ruby-font-size data))
      (obj/set! "lineHeight" "1")
      (obj/set! "textDecoration" "none")
      ;; Base-span styles that belong to the base glyphs, not the reading.
      (obj/unset! "textCombineUpright")
      (obj/unset! "textEmphasis")
      (obj/unset! "fontFeatureSettings")
      (obj/unset! "display")
      (obj/unset! "inlineSize")))

(defn- enum-or-default
  "Value of the Japanese layout enum `attr` in `data`, or its default when
   unset or unknown."
  [data attr]
  (let [value (get data attr)]
    (if (jl/valid-enum-value? attr value) value (jl/enum-default attr))))

(defn generate-ruby-container-styles
  [data]
  #js {:rubyPosition (enum-or-default data :ruby-side)
       :rubyAlign    (enum-or-default data :ruby-align)
       :rubyOverhang (enum-or-default data :ruby-overhang)})
