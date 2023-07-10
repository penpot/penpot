;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.modifiers
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.flex-layout.positions :as fpo]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))

(defn calc-fill-width-data
  "Calculates the size and modifiers for the width of an auto-fill child"
  [parent
   transform
   transform-inverse
   child
   child-origin child-width
   {:keys [children-data line-width to-bound-width] :as layout-data}]

  (cond
    (ctl/row? parent)
    (let [target-width (max (get-in children-data [(:id child) :child-width]) 0.01)
          fill-scale (/ target-width child-width)]
      {:width target-width
       :modifiers (ctm/resize-modifiers (gpt/point fill-scale 1) child-origin transform transform-inverse)})

    (ctl/col? parent)
    (let [line-width (min line-width (or to-bound-width line-width))
          target-width (max (- line-width (ctl/child-width-margin child)) 0.01)
          max-width (max (ctl/child-max-width child) 0.01)
          target-width (mth/clamp target-width (ctl/child-min-width child) max-width)
          fill-scale (/ target-width child-width)]
      {:width target-width
       :modifiers (ctm/resize-modifiers (gpt/point fill-scale 1) child-origin transform transform-inverse)})))

(defn calc-fill-height-data
  "Calculates the size and modifiers for the height of an auto-fill child"
  [parent
   transform transform-inverse
   child
   child-origin child-height
   {:keys [children-data line-height to-bound-height] :as layout-data}]

  (cond
    (ctl/col? parent)
    (let [target-height (max (get-in children-data [(:id child) :child-height]) 0.01)
          fill-scale (/ target-height child-height)]
      {:height target-height
       :modifiers (ctm/resize-modifiers (gpt/point 1 fill-scale) child-origin transform transform-inverse)})

    (ctl/row? parent)
    (let [line-height (min line-height (or to-bound-height line-height))
          target-height (max (- line-height (ctl/child-height-margin child)) 0.01)
          max-height (max (ctl/child-max-height child) 0.01)
          target-height (mth/clamp target-height (ctl/child-min-height child) max-height)
          fill-scale (/ target-height child-height)]
      {:height target-height
       :modifiers (ctm/resize-modifiers (gpt/point 1 fill-scale) child-origin transform transform-inverse)})))

(defn fill-modifiers
  [parent parent-bounds child child-bounds layout-line]
  (let [child-origin (gpo/origin child-bounds)
        child-width  (gpo/width-points child-bounds)
        child-height (gpo/height-points child-bounds)

        [_ transform transform-inverse]
        (when (or (ctl/fill-width? child) (ctl/fill-height? child))
          (gtr/calculate-geometry @parent-bounds))

        fill-width
        (when (ctl/fill-width? child)
          (calc-fill-width-data parent transform transform-inverse child child-origin child-width layout-line))

        fill-height
        (when (ctl/fill-height? child)
          (calc-fill-height-data parent transform transform-inverse child child-origin child-height layout-line))

        child-width (or (:width fill-width) child-width)
        child-height (or (:height fill-height) child-height)]

    [child-width
     child-height
     (-> (ctm/empty)
         (cond-> fill-width (ctm/add-modifiers (:modifiers fill-width)))
         (cond-> fill-height (ctm/add-modifiers (:modifiers fill-height))))]))

(defn layout-child-modifiers
  "Calculates the modifiers for the layout"
  [parent parent-bounds child child-bounds layout-line]
  (let [child-origin (gpo/origin child-bounds)

        [child-width child-height fill-modifiers]
        (fill-modifiers parent parent-bounds child child-bounds layout-line)

        [corner-p layout-line] (fpo/get-child-position parent child child-width child-height layout-line)
        move-vec (gpt/to-vec child-origin corner-p)

        modifiers
        (-> (ctm/empty)
            (ctm/add-modifiers fill-modifiers)
            (ctm/move move-vec))]
    [modifiers layout-line]))
