;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.text.styles
  (:require
   [cuerdas.core :as str]
   [app.main.fonts :as fonts]
   [app.common.data :as d]
   [app.util.object :as obj]
   [app.util.color :as uc]
   [app.util.text :as ut]))

(defn generate-root-styles
  ([props] (generate-root-styles (clj->js (obj/get props "node")) props))
  ([data props]
   (let [valign (obj/get data "vertical-align" "top")
         shape  (obj/get props "shape")
         base   #js {:height (or (:height shape) "100%")
                     :width (or (:width shape) "100%")}]
     (cond-> base
       (= valign "top")     (obj/set! "justifyContent" "flex-start")
       (= valign "center")  (obj/set! "justifyContent" "center")
       (= valign "bottom")  (obj/set! "justifyContent" "flex-end")
       ))))

(defn generate-paragraph-set-styles
  ([props] (generate-paragraph-set-styles (clj->js (obj/get props "node")) props))
  ([data props]
   ;; This element will control the auto-width/auto-height size for the
   ;; shape. The properties try to adjust to the shape and "overflow" if
   ;; the shape is not big enough.
   ;; We `inherit` the property `justify-content` so it's set by the root where
   ;; the property it's known.
   ;; `inline-flex` is similar to flex but `overflows` outside the bounds of the
   ;; parent
   (let [base #js {:display "inline-flex"
                   :flex-direction "column"
                   :justify-content "inherit"
                   :min-height "100%"
                   :min-width "100%"
                   :vertical-align "top"}]
     base)))

(defn generate-paragraph-styles
  ([props] (generate-paragraph-styles (clj->js (obj/get props "node")) props))
  ([data props]
   (let [shape  (obj/get props "shape")
         grow-type (:grow-type shape)
         base #js {:fontSize "14px"
                   :margin "inherit"
                   :lineHeight "1.2"}
         lh (obj/get data "line-height")
         ta (obj/get data "text-align")]
     (cond-> base
       ta (obj/set! "textAlign" ta)
       lh (obj/set! "lineHeight" lh)
       (= grow-type :auto-width) (obj/set! "whiteSpace" "pre")))))

(defn generate-text-styles
  ([props] (generate-text-styles (clj->js (obj/get props "node")) props))
  ([data props]
   (let [letter-spacing (obj/get data "letter-spacing")
         text-decoration (obj/get data "text-decoration")
         text-transform (obj/get data "text-transform")
         line-height (obj/get data "line-height")

         font-id (obj/get data "font-id" (:font-id ut/default-text-attrs))
         font-variant-id (obj/get data "font-variant-id")

         font-family (obj/get data "font-family")
         font-size  (obj/get data "font-size")

         ;; Old properties for backwards compatibility
         fill (obj/get data "fill")
         opacity (obj/get data "opacity" 1)

         fill-color (obj/get data "fill-color" fill)
         fill-opacity (obj/get data "fill-opacity" opacity)
         fill-color-gradient (obj/get data "fill-color-gradient" nil)
         fill-color-gradient (when fill-color-gradient
                               (-> (js->clj fill-color-gradient :keywordize-keys true)
                                   (update :type keyword)))

         ;; Uncomment this to allow to remove text colors. This could break the texts that already exist
         ;;[r g b a] (if (nil? fill-color)
         ;;            [0 0 0 0] ;; Transparent color
         ;;            (uc/hex->rgba fill-color fill-opacity))

         [r g b a] (uc/hex->rgba fill-color fill-opacity)

         text-color (if fill-color-gradient
                      (uc/gradient->css (js->clj fill-color-gradient))
                      (str/format "rgba(%s, %s, %s, %s)" r g b a))

         fontsdb (deref fonts/fontsdb)

         base #js {:textDecoration text-decoration
                   :textTransform text-transform
                   :lineHeight (or line-height "inherit")
                   :color text-color
                   "--text-color" text-color}]

     (when (and (string? letter-spacing)
                (pos? (alength letter-spacing)))
       (obj/set! base "letterSpacing" (str letter-spacing "px")))

     (when (and (string? font-size)
                (pos? (alength font-size)))
       (obj/set! base "fontSize" (str font-size "px")))

     (when (and (string? font-id)
                (pos? (alength font-id)))
       (fonts/ensure-loaded! font-id)
       (let [font (get fontsdb font-id)]
         (let [font-family (or (:family font)
                               (obj/get data "fontFamily"))
               font-variant (d/seek #(= font-variant-id (:id %))
                                    (:variants font))
               font-style  (or (:style font-variant)
                               (obj/get data "fontStyle"))
               font-weight (or (:weight font-variant)
                               (obj/get data "fontWeight"))]
           (obj/set! base "fontFamily" font-family)
           (obj/set! base "fontStyle" font-style)
           (obj/set! base "fontWeight" font-weight))))


     base)))
