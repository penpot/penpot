;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.modifiers
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.flex-layout.positions :as fpo]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))

(defn normalize-child-modifiers
  "Apply the modifiers and then normalized them against the parent coordinates"
  [modifiers {:keys [transform transform-inverse] :as parent} child-bounds parent-bounds transformed-parent-bounds]

  (let [transformed-child-bounds (gtr/transform-bounds child-bounds modifiers)

        child-bb-before (gpo/parent-coords-bounds child-bounds parent-bounds)
        child-bb-after  (gpo/parent-coords-bounds transformed-child-bounds transformed-parent-bounds)

        scale-x (/ (gpo/width-points child-bb-before) (gpo/width-points child-bb-after))
        scale-y (/ (gpo/height-points child-bb-before) (gpo/height-points child-bb-after))

        resize-vector (gpt/point scale-x scale-y)
        modif-transform (or (ctm/modifiers->transform modifiers) (gmt/matrix))
        modif-transform-inverse (gmt/inverse modif-transform)
        resize-transform (gmt/multiply modif-transform transform)
        resize-transform-inverse (gmt/multiply transform-inverse modif-transform-inverse)
        resize-origin (gpo/origin transformed-child-bounds)]

    (-> modifiers
        (ctm/select-child)
        (ctm/resize
         resize-vector
         resize-origin
         resize-transform
         resize-transform-inverse))))

(defn calc-fill-width-data
  "Calculates the size and modifiers for the width of an auto-fill child"
  [{:keys [transform transform-inverse] :as parent}
   child
   child-origin child-width
   {:keys [children-data line-width] :as layout-data}]

  (cond
    (ctl/row? parent)
    (let [target-width (max (get-in children-data [(:id child) :child-width]) 0.01)
          fill-scale (/ target-width child-width)]
      {:width target-width
       :modifiers (ctm/resize-modifiers (gpt/point fill-scale 1) child-origin transform transform-inverse)})

    (ctl/col? parent)
    (let [target-width (max (- line-width (ctl/child-width-margin child)) 0.01)
          max-width (ctl/child-max-width child)
          target-width (min max-width target-width)
          fill-scale (/ target-width child-width)]
      {:width target-width
       :modifiers (ctm/resize-modifiers (gpt/point fill-scale 1) child-origin transform transform-inverse)})))

(defn calc-fill-height-data
  "Calculates the size and modifiers for the height of an auto-fill child"
  [{:keys [transform transform-inverse] :as parent}
   child
   child-origin child-height
   {:keys [children-data line-height] :as layout-data}]

  (cond
    (ctl/col? parent)
    (let [target-height (max (get-in children-data [(:id child) :child-height]) 0.01)
          fill-scale (/ target-height child-height)]
      {:height target-height
       :modifiers (ctm/resize-modifiers (gpt/point 1 fill-scale) child-origin transform transform-inverse)})

    (ctl/row? parent)
    (let [target-height (max (- line-height (ctl/child-height-margin child)) 0.01)
          max-height (ctl/child-max-height child)
          target-height (min max-height target-height)
          fill-scale (/ target-height child-height)]
      {:height target-height
       :modifiers (ctm/resize-modifiers (gpt/point 1 fill-scale) child-origin transform transform-inverse)})))

(defn layout-child-modifiers
  "Calculates the modifiers for the layout"
  [parent child child-bounds layout-line]
  (let [child-origin (gpo/origin child-bounds)
        child-width  (gpo/width-points child-bounds)
        child-height (gpo/height-points child-bounds)

        fill-width   (when (ctl/fill-width? child)  (calc-fill-width-data parent child child-origin child-width layout-line))
        fill-height  (when (ctl/fill-height? child) (calc-fill-height-data parent child child-origin child-height layout-line))

        child-width (or (:width fill-width) child-width)
        child-height (or (:height fill-height) child-height)

        [corner-p layout-line] (fpo/get-child-position parent child child-width child-height layout-line)

        move-vec (gpt/to-vec child-origin corner-p)

        modifiers
        (-> (ctm/empty)
            (cond-> fill-width (ctm/add-modifiers (:modifiers fill-width)))
            (cond-> fill-height (ctm/add-modifiers (:modifiers fill-height)))
            (ctm/move move-vec))]

    [modifiers layout-line]))
