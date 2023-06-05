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
   [app.common.geom.shapes.grid-layout :as gsg]
   [app.common.geom.shapes.points :as gpo]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; Helper to debug the bounds when set the "hug" content property
(mf/defc debug-content-bounds
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

    (when (and shape (:layout shape))
      (let [children (->> (cph/get-immediate-children objects (:id shape))
                          (remove :hidden))
            bounds (d/lazy-map (keys objects) #(dm/get-in objects [% :points]))
            layout-bounds
            (cond (ctl/flex-layout? shape)
                  (gsl/layout-content-bounds bounds shape children)

                  (ctl/grid-layout? shape)
                  (gsg/layout-content-bounds bounds shape children))
            layout-points
            (cond (ctl/flex-layout? shape)
                  (flatten (gsl/layout-content-points bounds shape children))

                  (ctl/grid-layout? shape)
                  (flatten (gsg/layout-content-points bounds shape children)))]

        [:g.debug-layout {:pointer-events "none"}
         [:polygon {:points (->> layout-bounds (map #(dm/fmt "%, %" (:x %) (:y %))) (str/join " "))
                    :style  {:stroke "red" :fill "none"}}]

         [:*
          (for [p layout-points]
            [:circle {:cx (:x p)
                      :cy (:y p)
                      :r (/ 4 zoom)
                      :style {:fill "red"}}])]]))))

(mf/defc debug-layout-lines
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

    (when (and shape (ctl/flex-layout? shape))
      (let [row? (ctl/row? shape)
            col? (ctl/col? shape)

            children (->> (cph/get-immediate-children objects (:id shape))
                          (remove :hidden)
                          (map #(vector (gpo/parent-coords-bounds (:points %) (:points shape)) %)))
            layout-data (gsl/calc-layout-data shape children (:points shape))

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
                         (-> start-p (gpt/add (yv line-height)))]]

             [:g.layout-line {:key (dm/str "line-" idx)}
              [:polygon {:points (->> points (map #(dm/fmt "%, %" (:x %) (:y %))) (str/join " "))
                         :style {:stroke "red" :stroke-width (/ 2 zoom) :stroke-dasharray (dm/str (/ 10 zoom) " " (/ 5 zoom))}}]]))]))))

(mf/defc debug-drop-zones
  "Debug component to show the auto-layout drop areas"
  {::mf/wrap [#(mf/memo' % (mf/check-props ["objects" "selected-shapes" "hover-top-frame-id"]))]
   ::mf/wrap-props false}
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
      (let [drop-areas (gsl/get-drop-areas shape objects)]
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

(mf/defc shape-parent-bound
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shape" "parent"]))]
   ::mf/wrap-props false}
  [props]

  (let [shape (unchecked-get props "shape")
        parent (unchecked-get props "parent")
        zoom (unchecked-get props "zoom")
        [i1 i2 i3 i4] (gpo/parent-coords-bounds (:points shape) (:points parent))]
    [:*
     [:polygon {:points (->> [i1 i2 i3 i4] (map #(dm/fmt "%,%" (:x %) (:y %))) (str/join ","))
                :style {:fill "none" :stroke "red" :stroke-width (/ 1 zoom)}}]

     [:line {:x1 (:x i1)
             :y1 (:y i1)
             :x2 (:x i2)
             :y2 (:y i2)
             :style {:stroke "green" :stroke-width (/ 1 zoom)}}]
     [:line {:x1 (:x i1)
             :y1 (:y i1)
             :x2 (:x i4)
             :y2 (:y i4)
             :style {:stroke "blue" :stroke-width (/ 1 zoom)}}]]))

(mf/defc debug-parent-bounds
  {::mf/wrap-props false}
  [props]

  (let [objects            (unchecked-get props "objects")
        zoom               (unchecked-get props "zoom")
        selected-shapes    (unchecked-get props "selected-shapes")
        hover-top-frame-id (unchecked-get props "hover-top-frame-id")

        selected-frame
        (when (and (= (count selected-shapes) 1) (= :frame (-> selected-shapes first :type)))
          (first selected-shapes))

        parent (or selected-frame (get objects hover-top-frame-id))
        parent-bounds (:points parent)]

    (when (and (some? parent) (not= uuid/zero (:id parent)))
      (let [children (->> (cph/get-immediate-children objects (:id parent))
                          (remove :hidden))]
        [:g.debug-parent-bounds {:pointer-events "none"}
         (for [[idx child] (d/enumerate children)]
           [:*
            [:> shape-parent-bound {:key (dm/str "bound-" idx)
                                    :zoom zoom
                                    :shape child
                                    :parent parent}]

            (let [child-bounds (:points child)
                  points
                  (if (or (ctl/fill-height? child) (ctl/fill-height? child))
                    (gsl/child-layout-bound-points parent child parent-bounds child-bounds)
                    child-bounds)]
              (for [point points]
                [:circle {:cx (:x point)
                          :cy (:y point)
                          :r (/ 2 zoom)
                          :style {:fill "red"}}]))])]))))

(mf/defc debug-grid-layout
  {::mf/wrap-props false}
  [props]

  (let [objects            (unchecked-get props "objects")
        zoom               (unchecked-get props "zoom")
        selected-shapes    (unchecked-get props "selected-shapes")
        hover-top-frame-id (unchecked-get props "hover-top-frame-id")

        selected-frame
        (when (and (= (count selected-shapes) 1) (= :frame (-> selected-shapes first :type)))
          (first selected-shapes))

        parent (or selected-frame (get objects hover-top-frame-id))
        parent-bounds (:points parent)]

    (when (and (some? parent) (not= uuid/zero (:id parent)))
      (let [children (->> (cph/get-immediate-children objects (:id parent))
                          (remove :hidden)
                          (map #(vector (gpo/parent-coords-bounds (:points %) (:points parent)) %)))

            hv   #(gpo/start-hv parent-bounds %)
            vv   #(gpo/start-vv parent-bounds %)

            width (gpo/width-points parent-bounds)
            height (gpo/height-points parent-bounds)
            origin (gpo/origin parent-bounds)

            {:keys [row-tracks column-tracks]}
            (gsg/calc-layout-data parent children parent-bounds)]

        [:*
         (for [row-data row-tracks]
           (let [start-p (gpt/add origin (vv (:distance row-data)))
                 end-p (gpt/add start-p (hv width))]
             [:line {:x1 (:x start-p)
                     :y1 (:y start-p)
                     :x2 (:x end-p)
                     :y2 (:y end-p)
                     :style {:stroke "red"
                             :stroke-width (/ 1 zoom)}}]))

         (for [column-data column-tracks]
           (let [start-p (gpt/add origin (hv (:distance column-data)))
                 end-p (gpt/add start-p (vv height))]
             [:line {:x1 (:x start-p)
                     :y1 (:y start-p)
                     :x2 (:x end-p)
                     :y2 (:y end-p)
                     :style {:stroke "red"
                             :stroke-width (/ 1 zoom)}}]))]))))
