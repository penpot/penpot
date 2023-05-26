;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.grid-layout-viewer
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.grid-layout :as gsg]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.shape.layout :as ctl]
   [rumext.v2 :as mf]))

(mf/defc grid-cell-area-label
  {::mf/wrap-props false}
  [props]

  (let [cell-origin (unchecked-get props "origin")
        cell-width  (unchecked-get props "width")
        text        (unchecked-get props "text")

        area-width (* 10 (count text))
        area-height 25
        area-x (- (+ (:x cell-origin) cell-width) area-width)
        area-y (:y cell-origin)

        area-text-x (+ area-x (/ area-width 2))
        area-text-y (+ area-y (/ area-height 2))]

    [:g {:pointer-events "none"}
     [:rect {:x area-x
             :y area-y
             :width area-width
             :height area-height
             :style {:fill "var(--color-distance)"
                     :fill-opacity 0.3}}]
     [:text {:x area-text-x
             :y area-text-y
             :style {:fill "var(--color-distance)"
                     :font-family "worksans"
                     :font-weight 600
                     :font-size 14
                     :alignment-baseline "central"
                     :text-anchor "middle"}}
      text]]))

(mf/defc grid-cell
  {::mf/wrap-props false}
  [props]
  (let [shape       (unchecked-get props "shape")
        cell        (unchecked-get props "cell")
        layout-data (unchecked-get props "layout-data")

        cell-bounds (gsg/cell-bounds layout-data cell)
        cell-origin (gpo/origin cell-bounds)
        cell-width  (gpo/width-points cell-bounds)
        cell-height (gpo/height-points cell-bounds)
        cell-center (gsh/points->center cell-bounds)
        cell-origin (gpt/transform cell-origin (gmt/transform-in cell-center (:transform-inverse shape)))]

    [:g.cell
     [:rect
      {:transform (dm/str (gmt/transform-in cell-center (:transform shape)))
       :x (:x cell-origin)
       :y (:y cell-origin)
       :width cell-width
       :height cell-height
       :style {:stroke "var(--color-distance)"
               :stroke-width 1.5
               :fill "none"}}]

     (when (:area-name cell)
       [:& grid-cell-area-label {:origin cell-origin
                                 :width cell-width
                                 :text (:area-name cell)}])]))

(mf/defc grid-layout-viewer
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        childs (unchecked-get props "childs")

        children
        (->> childs
             (remove :hidden)
             (map #(vector (gpo/parent-coords-bounds (:points %) (:points shape)) %)))

        layout-data (gsg/calc-layout-data shape children (:points shape))]

    [:g.cells
     (for [cell (ctl/get-cells shape {:sort? true})]
       [:& grid-cell {:key (dm/str "cell-" (:id cell))
                      :shape shape
                      :layout-data layout-data
                      :cell cell}])]))
