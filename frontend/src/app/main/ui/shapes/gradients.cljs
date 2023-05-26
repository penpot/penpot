;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.gradients
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.export :as ed]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn add-metadata [props gradient]
  (-> props
      (obj/set! "penpot:gradient" "true")
      (obj/set! "penpot:start-x" (:start-x gradient))
      (obj/set! "penpot:start-x" (:start-x gradient))
      (obj/set! "penpot:start-y" (:start-y gradient))
      (obj/set! "penpot:end-x"   (:end-x gradient))
      (obj/set! "penpot:end-y"   (:end-y gradient))
      (obj/set! "penpot:width"   (:width gradient))))

(mf/defc linear-gradient [{:keys [id gradient shape]}]
  (let [transform (when (= :path (:type shape))
                    (gsh/transform-matrix shape nil (gpt/point 0.5 0.5)))

        base-props #js {:id id
                        :x1 (:start-x gradient)
                        :y1 (:start-y gradient)
                        :x2 (:end-x gradient)
                        :y2 (:end-y gradient)
                        :gradientTransform (dm/str transform)}

        include-metadata? (mf/use-ctx ed/include-metadata-ctx)

        props (cond-> base-props
          include-metadata?
          (add-metadata gradient))]

    [:> :linearGradient props
     (for [{:keys [offset color opacity]} (:stops gradient)]
       [:stop {:key (dm/str id "-stop-" offset)
               :offset (or offset 0)
               :stop-color color
               :stop-opacity opacity}])]))

(mf/defc radial-gradient [{:keys [id gradient shape]}]
  (let [path? (= :path (:type shape))
        shape-transform (or (when path? (:transform shape)) (gmt/matrix))
        shape-transform-inv (or (when path? (:transform-inverse shape)) (gmt/matrix))

        {:keys [start-x start-y end-x end-y] gwidth :width} gradient

        gradient-vec (gpt/to-vec (gpt/point start-x start-y)
                                 (gpt/point end-x end-y))

        angle (+ (gpt/angle gradient-vec) 90)

        bb-shape (gsh/shapes->rect [shape])

        ;; Paths don't have a transform in SVG because we transform the points
        ;; we need to compensate the difference between the original rectangle
        ;; and the transformed one. This factor is that calculation.
        factor (if path?
                 (/ (:height (:selrect shape)) (:height bb-shape))
                 1.0)

        transform (-> (gmt/matrix)
                      (gmt/translate (gpt/point start-x start-y))
                      (gmt/multiply shape-transform)
                      (gmt/rotate angle)
                      (gmt/scale (gpt/point gwidth factor))
                      (gmt/multiply shape-transform-inv)
                      (gmt/translate (gpt/negate (gpt/point start-x start-y))))

        gradient-radius (gpt/length gradient-vec)
        base-props #js {:id id
                        :cx start-x
                        :cy start-y
                        :r gradient-radius
                        :gradientTransform transform}

        include-metadata? (mf/use-ctx ed/include-metadata-ctx)

        props (cond-> base-props
                include-metadata?
                (add-metadata gradient))]
    [:> :radialGradient props
     (for [{:keys [offset color opacity]} (:stops gradient)]
       [:stop {:key (dm/str id "-stop-" offset)
               :offset (or offset 0)
               :stop-color color
               :stop-opacity opacity}])]))

(mf/defc gradient
  {::mf/wrap-props false}
  [props]
  (let [attr   (obj/get props "attr")
        shape  (obj/get props "shape")
        id     (obj/get props "id")
        id'    (mf/use-ctx muc/render-id)
        id     (or id (dm/str (name attr) "_" id'))
        gradient (get shape attr)
        gradient-props #js {:id id
                            :gradient gradient
                            :shape shape}]
    (when gradient
      (case (d/name (:type gradient))
        "linear" [:> linear-gradient gradient-props]
        "radial" [:> radial-gradient gradient-props]
        nil))))
