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
  [{:keys [width height]} node]
  (let [valign (:vertical-align node "top")
        base   #js {:height height
                    :width  width
                    :fontFamily "sourcesanspro"
                    :display "flex"
                    :whiteSpace "break-spaces"}]
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
  [_shape data]
  (let [line-height (:line-height data 1.2)
        text-align  (:text-align data "start")
        base        #js {:fontSize (str (:font-size data (:font-size txt/default-text-attrs)) "px")
                         :lineHeight (:line-height data (:line-height txt/default-text-attrs))
                         :margin 0}]
    (cond-> base
      (some? line-height)       (obj/set! "lineHeight" line-height)
      (some? text-align)        (obj/set! "textAlign" text-align))))

(defn generate-text-styles
  ([shape data]
   (generate-text-styles shape data nil))

  ([{:keys [grow-type] :as shape} data {:keys [show-text?] :or {show-text? true}}]
   (let [letter-spacing  (:letter-spacing data 0)
         text-decoration (:text-decoration data)
         text-transform  (:text-transform data)
         line-height     (:line-height data 1.2)

         font-id         (or (:font-id data)
                             (:font-id txt/default-text-attrs))

         font-variant-id (:font-variant-id data)

         font-size       (:font-size data)
         fill-color      (or (-> data :fills first :fill-color) (:fill-color data))
         fill-opacity    (or (-> data :fills first :fill-opacity) (:fill-opacity data))

         [r g b a]       (uc/hex->rgba fill-color fill-opacity)
         text-color      (when (and (some? fill-color) (some? fill-opacity))
                           (str/format "rgba(%s, %s, %s, %s)" r g b a))

         fontsdb         (deref fonts/fontsdb)

         base            #js {:textDecoration text-decoration
                              :textTransform text-transform
                              :lineHeight (or line-height "1.2")
                              :color (if show-text? text-color "transparent")
                              :caretColor (or text-color "black")
                              :overflowWrap "initial"
                              :lineBreak "auto"
                              :whiteSpace "break-spaces"}

         fills
         (cond
           (some? (:fills data))
           (:fills data)

           ;; DEPRECATED: still here for backward compatibility with
           ;; old penpot files that still has a single color.
           (or (some? (:fill-color data))
               (some? (:fill-opacity data))
               (some? (:fill-color-gradient data)))
           [(d/without-nils (select-keys data [:fill-color :fill-opacity :fill-color-gradient
                                               :fill-color-ref-id :fill-color-ref-file]))]

           (nil? (:fills data))
           [{:fill-color "#000000" :fill-opacity 1}])

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
       (obj/set! "whiteSpace" "pre")))))
