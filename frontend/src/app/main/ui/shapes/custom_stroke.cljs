;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.custom-stroke
  (:require
   [rumext.alpha :as mf]
   [app.common.uuid :as uuid]
   [app.common.geom.shapes :as geom]
   [app.util.object :as obj]))

; The SVG standard does not implement yet the 'stroke-alignment'
; attribute, to define the position of the stroke relative to the
; stroke axis (inner, center, outer). Here we implement a patch to be
; able to draw the stroke in the three cases. See discussion at:
; https://stackoverflow.com/questions/7241393/can-you-control-how-an-svgs-stroke-width-is-drawn
(mf/defc shape-custom-stroke
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        base-props (unchecked-get props "base-props")
        elem-name (unchecked-get props "elem-name")
        base-style (obj/get base-props "style")
        {:keys [x y width height]} (:selrect shape)
        stroke-id (mf/use-var (uuid/next))
        stroke-style (:stroke-style shape :none)
        stroke-position (:stroke-alignment shape :center)]
    (cond
      ;; Center alignment (or no stroke): the default in SVG
      (or (= stroke-style :none) (= stroke-position :center))
      [:> elem-name (obj/merge! #js {} base-props)]

      ;; Inner alignment: display the shape with double width stroke,
      ;; and clip the result with the original shape without stroke.
      (= stroke-position :inner)
      (let [clip-id (str "clip-" @stroke-id)

            clip-props (obj/merge
                         base-props
                         #js {:transform nil
                              :style (obj/merge
                                       base-style
                                       #js {:stroke nil
                                            :strokeWidth nil
                                            :strokeOpacity nil
                                            :strokeDasharray nil
                                            :fill "white"
                                            :fillOpacity 1})})

            stroke-width (obj/get base-style "strokeWidth" 0)
            shape-props (obj/merge
                          base-props
                          #js {:clipPath (str "url('#" clip-id "')")
                               :style (obj/merge
                                        base-style
                                        #js {:strokeWidth (* stroke-width 2)})})]
        [:*
         [:> "clipPath" #js {:id clip-id}
          [:> elem-name clip-props]]
         [:> elem-name shape-props]])

      ;; Outer alingmnent: display the shape in two layers. One
      ;; without stroke (only fill), and another one only with stroke
      ;; at double width (transparent fill) and passed through a mask
      ;; that shows the whole shape, but hides the original shape
      ;; without stroke

      (= stroke-position :outer)
      (let [stroke-mask-id (str "mask-" @stroke-id)
            stroke-width   (obj/get base-style "strokeWidth" 0)
            mask-props1 (obj/merge
                          base-props
                          #js {:transform nil
                               :style (obj/merge
                                        base-style
                                        #js {:stroke "white"
                                             :strokeWidth (* stroke-width 2)
                                             :strokeOpacity 1
                                             :strokeDasharray nil
                                             :fill "white"
                                             :fillOpacity 1})})
            mask-props2 (obj/merge
                          base-props
                          #js {:transform nil
                               :style (obj/merge
                                        base-style
                                        #js {:stroke nil
                                             :strokeWidth nil
                                             :strokeOpacity nil
                                             :strokeDasharray nil
                                             :fill "black"
                                             :fillOpacity 1})})

            shape-props1 (obj/merge
                           base-props
                           #js {:style (obj/merge
                                         base-style
                                         #js {:stroke nil
                                              :strokeWidth nil
                                              :strokeOpacity nil
                                              :strokeDasharray nil})})
            shape-props2 (obj/merge
                           base-props
                           #js {:mask (str "url('#" stroke-mask-id "')")
                                :style (obj/merge
                                         base-style
                                         #js {:strokeWidth (* stroke-width 2)
                                              :fill "none"
                                              :fillOpacity 0})})]
        [:*
         [:mask {:id stroke-mask-id}
          [:> elem-name mask-props1]
          [:> elem-name mask-props2]]
         [:> elem-name shape-props1]
         [:> elem-name shape-props2]]))))

