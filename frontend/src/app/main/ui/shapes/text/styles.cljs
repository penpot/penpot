;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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
   [cuerdas.core :as str]))

(defn generate-root-styles
  ([props node]
   (generate-root-styles props node false))
  ([{:keys [width height]} node code?]
   (let [valign (:vertical-align node "top")
         ;; Mirroring the shape's writing mode on the root makes paragraph
         ;; blocks stack right-to-left.
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

        text-align  (:text-align data "start")
        writing-mode (:writing-mode data)
        text-orientation (:text-orientation data)
        base        #js {;; Fix a problem when exporting HTML
                         :fontSize 0
                         :lineHeight line-height
                         :margin 0}]

    (cond-> base
      (some? line-height)       (obj/set! "lineHeight" line-height)
      (some? text-align)        (obj/set! "textAlign" text-align)
      (some? writing-mode)      (obj/set! "writingMode" writing-mode)
      (some? writing-mode)      (obj/set! "textSpacingTrim" "normal")
      (= writing-mode "vertical-rl") (obj/set! "textAutospace" "normal")
      (some? text-orientation)  (obj/set! "textOrientation" text-orientation))))

(defn css-text-combine-upright
  "CSS value for a persisted text-combine-upright: the digits variants
   serialize with their max run length per the CSS `digits <n>` syntax."
  [value]
  (case value
    "digits"  "digits 4"
    "digits2" "digits 2"
    "digits3" "digits 3"
    value))

(defn generate-text-styles
  ([shape data]
   (generate-text-styles shape data nil))

  ([{:keys [grow-type] :as shape} data {:keys [show-text?] :or {show-text? true}}]
   (let [letter-spacing  (:letter-spacing data 0)
         text-combine-upright (:text-combine-upright data)
         text-emphasis   (:text-emphasis data)
         font-features   (:font-features data)
         annotation-clearance (:annotation-clearance data)
         annotation-layers (if (= "auto" annotation-clearance)
                             (+ (if (and (not (true? (:ruby-hidden data)))
                                         (string? (:ruby data))
                                         (seq (:ruby data))) 1 0)
                                (if (and (string? text-emphasis)
                                         (not= "none" text-emphasis)) 1 0))
                             0)
         line-height-num (js/parseFloat (or (:line-height data)
                                            (:line-height txt/default-typography)))
         auto-line-height (when (and (pos? annotation-layers)
                                     (not (js/isNaN line-height-num)))
                            (+ line-height-num (* annotation-layers 0.5)))
         warichu?        (and (= "warichu" (:warichu data))
                              (string? (:text data))
                              (>= (count (:text data)) 2))
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

       (and (string? text-combine-upright) (pos? (alength text-combine-upright)))
       (obj/set! "textCombineUpright" (css-text-combine-upright text-combine-upright))

       ;; Emphasis marks map to CSS text-emphasis-style: our kebab values
       ;; ("filled-dot") become the CSS "<fill> <shape>" pair ("filled dot").
       (and (string? text-emphasis) (pos? (alength text-emphasis))
            (not= "none" text-emphasis))
       (obj/set! "textEmphasis" (str/replace text-emphasis "-" " "))

       (and (string? font-features) (pos? (alength font-features))
            (not= "none" font-features))
       (obj/set! "fontFeatureSettings" (str/format "\"%s\"" font-features))

       (and (string? annotation-clearance)
            (pos? (alength annotation-clearance)))
       (obj/set! "--annotation-clearance" annotation-clearance)

       (some? auto-line-height)
       (obj/set! "lineHeight" auto-line-height)

       (and (string? font-size) (pos? (alength font-size)))
       (obj/set! "fontSize" (str font-size "px"))

       (some? font)
       (-> (obj/set! "fontFamily" font-family)
           (obj/set! "fontStyle" font-style)
           (obj/set! "fontWeight" font-weight))

       (= grow-type :auto-width)
       (obj/set! "whiteSpace" "pre")

       ;; Warichu (割注) CSS emulation: an inline-block at half size whose
       ;; inline-size fits half the characters, so the browser wraps it into
       ;; two half-size sub-lines within one inline position in either writing
       ;; mode.
       warichu?
       (-> (obj/set! "display" "inline-block")
           (obj/set! "fontSize" (if (and (string? font-size) (pos? (alength font-size)))
                                  (str (/ (js/parseFloat font-size) 2) "px")
                                  "50%"))
           (obj/set! "lineHeight" "1")
           (obj/set! "inlineSize"
                     (str (js/Math.ceil (/ (alength (js/Array.from (:text data))) 2)) "em")))))))

(defn generate-ruby-styles
  [shape data]
  (-> (generate-text-styles shape data)
      (obj/set! "fontSize" (case (:ruby-size data)
                             "third" "33.333333%"
                             "quarter" "25%"
                             "50%"))
      (obj/set! "lineHeight" "1")
      (obj/set! "textDecoration" "none")
      (obj/unset! "textCombineUpright")))

(defn generate-ruby-container-styles
  [data]
  #js {:rubyPosition (if (= "under" (:ruby-side data)) "under" "over")
       :rubyAlign (case (:ruby-align data)
                    "center" "center"
                    "start" "start"
                    "space-between" "space-between"
                    "space-around")
       :rubyOverhang (if (= "none" (:ruby-overhang data)) "none" "auto")})
