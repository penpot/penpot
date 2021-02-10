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
   [app.util.object :as obj]
   [app.common.uuid :as uuid]
   [app.main.ui.context :as muc]
   [app.common.geom.point :as gpt]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes :as gsh]))

(mf/defc linear-gradient [{:keys [id gradient shape]}]
  (let [{:keys [x y width height]} (:selrect shape)
        transform (when (= :path (:type shape)) (gsh/transform-matrix shape nil (gpt/point 0.5 0.5)))]
    [:linearGradient {:id id
                      :x1 (:start-x gradient)
                      :y1 (:start-y gradient)
                      :x2 (:end-x gradient)
                      :y2 (:end-y gradient)
                      :gradientTransform transform}
     (for [{:keys [offset color opacity]} (:stops gradient)]
       [:stop {:key (str id "-stop-" offset)
               :offset (or offset 0)
               :stop-color color
               :stop-opacity opacity}])]))

(mf/defc radial-gradient [{:keys [id gradient shape]}]
  (let [{:keys [x y width height]} (:selrect shape)
        center (gsh/center-shape shape)
        transform (when (= :path (:type shape)) (gsh/transform-matrix shape))]
    (let [[x y] (if (= (:type shape) :frame) [0 0] [x y])
          translate-vec (gpt/point (+ x (* width (:start-x gradient)))
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
                               (* scale-factor-x (/ width 2)))

          transform (gmt/multiply transform
                                  (gmt/translate-matrix translate-vec)
                                  (gmt/rotate-matrix angle)
                                  (gmt/scale-matrix scale-vec))]
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
                 :stop-opacity opacity}])])))

(mf/defc gradient
  {::mf/wrap-props false}
  [props]
  (let [attr (obj/get props "attr")
        shape (obj/get props "shape")
        render-id (mf/use-ctx muc/render-ctx)
        id (str (name attr) "_" render-id)
        gradient (get shape attr)
        gradient-props #js {:id id
                            :gradient gradient
                            :shape shape}]
    (when gradient
      (case (:type gradient)
        :linear [:> linear-gradient gradient-props]
        :radial [:> radial-gradient gradient-props]))))
