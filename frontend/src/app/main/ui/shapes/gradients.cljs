;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.gradients
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.export :as ed]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn- add-metadata!
  [props gradient]
  (-> props
      (obj/set! "penpot:gradient" "true")
      (obj/set! "penpot:start-x" (:start-x gradient))
      (obj/set! "penpot:start-y" (:start-y gradient))
      (obj/set! "penpot:end-x"   (:end-x gradient))
      (obj/set! "penpot:end-y"   (:end-y gradient))
      (obj/set! "penpot:width"   (:width gradient))))

(mf/defc linear-gradient
  {::mf/wrap-props false}
  [{:keys [id gradient shape force-transform]}]
  (let [transform (mf/with-memo [shape]
                    (when force-transform
                      (gsh/transform-matrix shape nil (gpt/point 0.5 0.5))))

        metadata? (mf/use-ctx ed/include-metadata-ctx)
        props     #js {:id id
                       :x1 (:start-x gradient)
                       :y1 (:start-y gradient)
                       :x2 (:end-x gradient)
                       :y2 (:end-y gradient)
                       :gradientTransform (dm/str transform)}]

    (when ^boolean metadata?
      (add-metadata! props gradient))

    [:> :linearGradient props
     (for [[index {:keys [offset color opacity]}] (d/enumerate (sort-by :offset (:stops gradient)))]
       [:stop {:key (dm/str id "-stop-" index)
               :offset (d/nilv offset 0)
               :stop-color color
               :stop-opacity opacity}])]))

(mf/defc radial-gradient
  {::mf/wrap-props false}
  [{:keys [id gradient shape]}]
  (let [path?         (cfh/path-shape? shape)

        transform     (when ^boolean path?
                        (dm/get-prop shape :transform))
        transform     (d/nilv transform gmt/base)

        transform-inv (when ^boolean path?
                        (dm/get-prop shape :transform-inverse))
        transform-inv (d/nilv transform-inv gmt/base)

        {:keys [start-x start-y end-x end-y] gwidth :width} gradient

        gstart-pt     (gpt/point start-x start-y)
        gend-pt       (gpt/point end-x end-y)
        gradient-vec  (gpt/to-vec gstart-pt gend-pt)

        angle         (+ (gpt/angle gradient-vec) 90)

        points        (dm/get-prop shape :points)
        bounds        (mf/with-memo [points]
                        (grc/points->rect points))
        selrect       (dm/get-prop shape :selrect)

        ;; Paths don't have a transform in SVG because we transform
        ;; the points we need to compensate the difference between the
        ;; original rectangle and the transformed one. This factor is
        ;; that calculation.
        factor        (if ^boolean path?
                        (/ (dm/get-prop selrect :height)
                           (dm/get-prop bounds :height))
                        1.0)

        transform     (mf/with-memo [gradient transform transform-inv factor]
                        (-> (gmt/matrix)
                            (gmt/translate gstart-pt)
                            (gmt/multiply transform)
                            (gmt/rotate angle)
                            (gmt/scale (gpt/point gwidth factor))
                            (gmt/multiply transform-inv)
                            (gmt/translate (gpt/negate gstart-pt))))

        metadata?     (mf/use-ctx ed/include-metadata-ctx)

        props         #js {:id id
                           :cx start-x
                           :cy start-y
                           :r (gpt/length gradient-vec)
                           :gradientTransform transform}]

    (when ^boolean metadata?
      (add-metadata! props gradient))

    [:> :radialGradient props
     (for [[index {:keys [offset color opacity]}] (d/enumerate (:stops gradient))]
       [:stop {:key (dm/str id "-stop-" index)
               :offset (d/nilv offset 0)
               :stop-color color
               :stop-opacity opacity}])]))

(mf/defc gradient
  {::mf/wrap-props false}
  [props]
  (let [attr     (unchecked-get props "attr")
        shape    (unchecked-get props "shape")
        id       (unchecked-get props "id")
        rid      (mf/use-ctx muc/render-id)

        id       (if (some? id)
                   id
                   (dm/str (name attr) "-" rid))

        gradient (get shape attr)
        props    #js {:id id
                      :gradient gradient
                      :shape shape}]

    (when (some? gradient)
      (case (:type gradient)
        :linear [:> linear-gradient props]
        :radial [:> radial-gradient props]
        nil))))
