;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.gradients
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [goog.object :as gobj]
   [app.common.uuid :as uuid]
   [app.common.geom.point :as gpt]))

(mf/defc linear-gradient [{:keys [id shape gradient]}]
  (let [{:keys [x y width height]} shape]
    [:defs
     [:linearGradient {:id id
                       :x1 (:start-x gradient)
                       :y1 (:start-y gradient)
                       :x2 (:end-x gradient)
                       :y2 (:end-y gradient)}
      (for [{:keys [offset color opacity]} (:stops gradient)]
        [:stop {:key (str id "-stop-" offset)
                :offset (or offset 0)
                :stop-color color
                :stop-opacity opacity}])]]))

(mf/defc radial-gradient [{:keys [id shape gradient]}]
  (let [{:keys [x y width height]} shape]
    [:defs
     (let [translate-vec (gpt/point (+ x (* width (:start-x gradient)))
                                    (+ y (* height (:start-y gradient))))

           
           gradient-vec (gpt/to-vec (gpt/point (* width (:start-x gradient))
                                               (* height (:start-y gradient)))
                                    (gpt/point (* width (:end-x gradient))
                                               (* height (:end-y gradient))))

           angle (gpt/angle gradient-vec
                            (gpt/point 1 0))

           shape-height-vec (gpt/point 0 (/ height 2))

           scale-factor-y (/ (gpt/length gradient-vec) (/ height 2))
           scale-factor-x (* scale-factor-y (:width gradient))

           scale-vec (gpt/point (* scale-factor-y (/ height 2))
                                (* scale-factor-x (/ width 2))
                                ) 
           tr-translate (str/fmt "translate(%s, %s)" (:x translate-vec) (:y translate-vec))
           tr-rotate (str/fmt "rotate(%s)" angle)
           tr-scale (str/fmt "scale(%s, %s)" (:x scale-vec) (:y scale-vec))
           transform (str/fmt "%s %s %s" tr-translate tr-rotate tr-scale)]
       [:radialGradient {:id id
                         :cx 0
                         :cy 0
                         :r 1
                         :gradientUnits "userSpaceOnUse"
                         :gradientTransform transform}
        (for [{:keys [offset color opacity]} (:stops gradient)]
          [:stop {:key (str id "-stop-" offset)
                  :offset (or offset 0)
                  :stop-color color
                  :stop-opacity opacity}])])]))

(mf/defc gradient
  {::mf/wrap-props false}
  [props]
  (let [gradient (gobj/get props "gradient")]
    (case (:type gradient)
      :linear [:> linear-gradient props]
      :radial [:> radial-gradient props]
      nil)))
