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
   [app.common.geom.shapes.transforms :as gst]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))


(defn normalize-child-modifiers
  "Apply the modifiers and then normalized them against the parent coordinates"
  [parent child modifiers {:keys [transform transform-inverse] :as transformed-parent}]

  (let [transformed-child (gst/transform-shape child modifiers)
        child-bb-before (gst/parent-coords-rect child parent)
        child-bb-after  (gst/parent-coords-rect transformed-child transformed-parent)
        scale-x (/ (:width child-bb-before) (:width child-bb-after))
        scale-y (/ (:height child-bb-before) (:height child-bb-after))

        resize-origin (-> transformed-parent :points first) ;; TODO LAYOUT: IS always the origin?n
        resize-vector (gpt/point scale-x scale-y)]
    (-> modifiers
        (ctm/select-child-modifiers)
        (ctm/set-resize resize-vector resize-origin transform transform-inverse))))

(defn calc-fill-width-data
  "Calculates the size and modifiers for the width of an auto-fill child"
  [{:keys [transform transform-inverse] :as parent}
   {:keys [layout-h-behavior] :as child}
   child-origin child-width
   {:keys [num-children line-width line-fill? child-fill? layout-bounds] :as layout-data}]

  (let [[layout-gap-row _] (ctl/gaps parent)]
    (cond
      (and (ctl/row? parent) (= :fill layout-h-behavior) child-fill?)
      (let [layout-width (gpo/width-points layout-bounds)
            fill-space (- layout-width line-width (* layout-gap-row (dec num-children)))
            fill-width (/ fill-space (:num-child-fill layout-data))
            fill-scale (/ fill-width child-width)]

        {:width fill-width
         :modifiers (ctm/resize (gpt/point fill-scale 1) child-origin transform transform-inverse)})

      (and (ctl/col? parent) (= :fill layout-h-behavior) line-fill?)
      (let [fill-scale (/ line-width child-width)]
        {:width line-width
         :modifiers (ctm/resize (gpt/point fill-scale 1) child-origin transform transform-inverse)}))))

(defn calc-fill-height-data
  "Calculates the size and modifiers for the height of an auto-fill child"
  [{:keys [transform transform-inverse] :as parent}
   {:keys [layout-v-behavior] :as child}
   child-origin child-height
   {:keys [num-children line-height layout-bounds line-fill? child-fill?] :as layout-data}]

  (let [[_ layout-gap-col] (ctl/gaps parent)]
    (cond
      (and (ctl/col? parent) (= :fill layout-v-behavior) child-fill?)
      (let [layout-height (gpo/height-points layout-bounds)
            fill-space (- layout-height line-height (* layout-gap-col (dec num-children)))
            fill-height (/ fill-space (:num-child-fill layout-data))
            fill-scale (/ fill-height child-height)]
        {:height fill-height
         :modifiers (ctm/resize (gpt/point 1 fill-scale) child-origin transform transform-inverse)})

      (and (ctl/row? parent) (= :fill layout-v-behavior) line-fill?)
      (let [fill-scale (/ line-height child-height)]
        {:height line-height
         :modifiers (ctm/resize (gpt/point 1 fill-scale) child-origin transform transform-inverse)}))))

(defn calc-layout-modifiers
  "Calculates the modifiers for the layout"
  [parent child layout-line]
  (let [child-bounds (gst/parent-coords-points child parent)

        child-origin (gpo/origin child-bounds)
        child-width  (gpo/width-points child-bounds)
        child-height (gpo/height-points child-bounds)

        fill-width   (calc-fill-width-data parent child child-origin child-width layout-line)
        fill-height  (calc-fill-height-data parent child child-origin child-height layout-line)

        child-width (or (:width fill-width) child-width)
        child-height (or (:height fill-height) child-height)

        [corner-p layout-line] (fpo/get-child-position parent child-width child-height layout-line)

        move-vec (gpt/to-vec child-origin corner-p)

        modifiers
        (-> (ctm/empty-modifiers)
            (cond-> fill-width (ctm/add-modifiers (:modifiers fill-width)))
            (cond-> fill-height (ctm/add-modifiers (:modifiers fill-height)))
            (ctm/set-move move-vec))]

    [modifiers layout-line]))
