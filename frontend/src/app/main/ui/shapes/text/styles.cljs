;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.text.styles
  (:require
   [app.common.data :as d]
   [app.common.text :as txt]
   [app.common.transit :as transit]
   [app.main.fonts :as fonts]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(defn generate-root-styles
  [_shape node]
  (let [valign (:vertical-align node "top")
        base   #js {:height "100%"
                    :width  "100%"
                    :fontFamily "sourcesanspro"
                    :display "flex"}]
    (cond-> base
      (= valign "top")     (obj/set! "alignItems" "flex-start")
      (= valign "center")  (obj/set! "alignItems" "center")
      (= valign "bottom")  (obj/set! "alignItems" "flex-end"))))

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
  [shape data]
  (let [line-height (:line-height data 1.2)
        text-align  (:text-align data "start")
        grow-type   (:grow-type shape)

        base        #js {:fontSize (str (:font-size data (:font-size txt/default-text-attrs)) "px")
                         :lineHeight (:line-height data (:line-height txt/default-text-attrs))
                         :margin "inherit"}]
    (cond-> base
      (some? line-height)       (obj/set! "lineHeight" line-height)
      (some? text-align)        (obj/set! "textAlign" text-align)
      (= grow-type :auto-width) (obj/set! "whiteSpace" "pre"))))

(defn generate-text-styles
  [data]
  (let [letter-spacing  (:letter-spacing data 0)
        text-decoration (:text-decoration data)
        text-transform  (:text-transform data)
        line-height     (:line-height data 1.2)

        font-id         (:font-id data (:font-id txt/default-text-attrs))
        font-variant-id (:font-variant-id data)

        font-size       (:font-size data)
        fill-color      (:fill-color data)
        fill-opacity    (:fill-opacity data)

        [r g b a]       (uc/hex->rgba fill-color fill-opacity)
        text-color      (when (and (some? fill-color) (some? fill-opacity))
                          (str/format "rgba(%s, %s, %s, %s)" r g b a))

        fontsdb         (deref fonts/fontsdb)

        base            #js {:textDecoration text-decoration
                             :textTransform text-transform
                             :lineHeight (or line-height "inherit")
                             :color "transparent"
                             :caretColor (or text-color "black")
                             :overflowWrap "initial"}

        base (-> base
                 (obj/set! "--fill-color" fill-color)
                 (obj/set! "--fill-color-gradient" (transit/encode-str (:fill-color-gradient data)))
                 (obj/set! "--fill-opacity" fill-opacity))]

    (when (and (string? letter-spacing)
               (pos? (alength letter-spacing)))
      (obj/set! base "letterSpacing" (str letter-spacing "px")))

    (when (and (string? font-size)
               (pos? (alength font-size)))
      (obj/set! base "fontSize" (str font-size "px")))

    (when (and (string? font-id)
               (pos? (alength font-id)))
      (fonts/ensure-loaded! font-id)
      (let [font         (get fontsdb font-id)
            font-family  (str/quote
                          (or (:family font)
                              (:font-family data)))
            font-variant (d/seek #(= font-variant-id (:id %))
                                 (:variants font))
            font-style   (or (:style font-variant)
                             (:font-style data))
            font-weight  (or (:weight font-variant)
                             (:font-weight data))]
        (obj/set! base "fontFamily" font-family)
        (obj/set! base "fontStyle" font-style)
        (obj/set! base "fontWeight" font-weight)))

    base))
