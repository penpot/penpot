;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.debug
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.flex-layout :as gsl]
   [app.common.geom.shapes.points :as gpo]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; Helper to debug the bounds when set the "hug" content property
#_(mf/defc debug-layout
  "Debug component to show the auto-layout drop areas"
  {::mf/wrap-props false}
  [props]

  (let [objects            (unchecked-get props "objects")
        selected-shapes    (unchecked-get props "selected-shapes")
        hover-top-frame-id (unchecked-get props "hover-top-frame-id")

        selected-frame
        (when (and (= (count selected-shapes) 1) (= :frame (-> selected-shapes first :type)))
          (first selected-shapes))

        shape (or selected-frame (get objects hover-top-frame-id))]

    (when (and shape (:layout shape))
      (let [children (cph/get-immediate-children objects (:id shape))
            layout-data (gsl/calc-layout-data shape children)

            {pad-top :p1 pad-right :p2 pad-bottom :p3 pad-left :p4} (:layout-padding shape)
            pad-top (or pad-top 0)
            pad-right (or pad-right 0)
            pad-bottom (or pad-bottom 0)
            pad-left (or pad-left 0)

            layout-bounds (gsl/layout-content-bounds shape children)]
        [:g.debug-layout {:pointer-events "none"
                          :transform (gsh/transform-str shape)}


         [:rect {:x      (:x layout-bounds)
                 :y      (:y layout-bounds)
                 :width  (:width layout-bounds)
                 :height (:height layout-bounds)
                 :style  {:stroke "red"
                          :fill "none"}}]]))))

(mf/defc debug-layout
  "Debug component to show the auto-layout drop areas"
  {::mf/wrap-props false}
  [props]

  (let [objects            (unchecked-get props "objects")
        zoom               (unchecked-get props "zoom")
        selected-shapes    (unchecked-get props "selected-shapes")
        hover-top-frame-id (unchecked-get props "hover-top-frame-id")

        selected-frame
        (when (and (= (count selected-shapes) 1) (= :frame (-> selected-shapes first :type)))
          (first selected-shapes))

        shape (or selected-frame (get objects hover-top-frame-id))]

    (when (and shape (ctl/layout? shape))
      (let [row? (ctl/row? shape)
            col? (ctl/col? shape)

            children (cph/get-immediate-children objects (:id shape))
            layout-data (gsl/calc-layout-data shape children)

            layout-bounds (:layout-bounds layout-data)
            xv   #(gpo/start-hv layout-bounds %)
            yv   #(gpo/start-vv layout-bounds %)]
        [:g.debug-layout {:pointer-events "none"}
         (for [[idx {:keys [start-p line-width line-height layout-gap-row layout-gap-col num-children]}] (d/enumerate (:layout-lines layout-data))]
           (let [line-width (if row? (+ line-width (* (dec num-children) layout-gap-row)) line-width)
                 line-height (if col? (+ line-height (* (dec num-children) layout-gap-col)) line-height)

                 points [start-p
                         (-> start-p (gpt/add (xv line-width)))
                         (-> start-p (gpt/add (xv line-width)) (gpt/add (yv line-height)))
                         (-> start-p (gpt/add (yv line-height)))
                         ]]
             [:g.layout-line {:key (dm/str "line-" idx)}
              [:polygon {:points (->> points (map #(dm/fmt "%, %" (:x %) (:y %))) (str/join " "))
                         :style {:stroke "red" :stroke-width (/ 2 zoom) :stroke-dasharray (dm/str (/ 10 zoom) " " (/ 5 zoom))}}]]))]))))

(mf/defc debug-drop-zones
  "Debug component to show the auto-layout drop areas"
  {::mf/wrap-props false}
  [props]

  (let [objects            (unchecked-get props "objects")
        zoom               (unchecked-get props "objects")
        selected-shapes    (unchecked-get props "selected-shapes")
        hover-top-frame-id (unchecked-get props "hover-top-frame-id")

        selected-frame
        (when (and (= (count selected-shapes) 1) (= :frame (-> selected-shapes first :type)))
          (first selected-shapes))

        shape (or selected-frame (get objects hover-top-frame-id))]

    (when (and shape (:layout shape))
      (let [children (cph/get-immediate-children objects (:id shape))
            layout-data (gsl/calc-layout-data shape children)
            drop-areas (gsl/layout-drop-areas shape layout-data children)]
        [:g.debug-layout {:pointer-events "none"
                          :transform (gsh/transform-str shape)}
         (for [[idx drop-area] (d/enumerate drop-areas)]
           [:g.drop-area {:key (dm/str "drop-area-" idx)}
            [:rect {:x (:x drop-area)
                    :y (:y drop-area)
                    :width (:width drop-area)
                    :height (:height drop-area)
                    :style {:fill "blue"
                            :fill-opacity 0.3
                            :stroke "red"
                            :stroke-width (/ zoom 1)
                            :stroke-dasharray (dm/str (/ 3 zoom) " " (/ 6 zoom))}}]
            [:text {:x (:x drop-area)
                    :y (:y drop-area)
                    :width (:width drop-area)
                    :height (:height drop-area)
                    :alignment-baseline "hanging"
                    :fill "black"}
             (:index drop-area)]])]))))
