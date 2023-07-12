;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.pixel-precision
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]))

(defn size-pixel-precision
  [modifiers shape points precision]
  (let [origin            (gpo/origin points)
        curr-width        (gpo/width-points points)
        curr-height       (gpo/height-points points)

        center            (gco/points->center points)
        selrect           (gtr/calculate-selrect points center)

        transform         (gtr/calculate-transform points center selrect)
        transform-inverse (when (some? transform) (gmt/inverse transform))

        path?             (cph/path-shape? shape)
        vertical-line?    (and path? (<= curr-width 0.01))
        horizontal-line?  (and path? (<= curr-height 0.01))

        target-width      (if vertical-line? curr-width (mth/max 1 (mth/round curr-width precision)))
        target-height     (if horizontal-line? curr-height (mth/max 1 (mth/round curr-height precision)))

        ratio-width       (/ target-width curr-width)
        ratio-height      (/ target-height curr-height)
        scalev            (gpt/point ratio-width ratio-height)]

    (ctm/resize modifiers scalev origin transform transform-inverse {:precise? true})))

(defn position-pixel-precision
  [modifiers _ points precision ignore-axis]
  (let [bounds        (grc/bounds->rect points)
        corner        (gpt/point bounds)
        target-corner
        (cond-> corner
          (= ignore-axis :x)
          (update :y mth/round precision)

          (= ignore-axis :y)
          (update :x mth/round precision)

          (nil? ignore-axis)
          (gpt/round-step precision))
        deltav        (gpt/to-vec corner target-corner)]
    (ctm/move modifiers deltav)))

(defn set-pixel-precision
  "Adjust modifiers so they adjust to the pixel grid"
  [modifiers shape precision ignore-axis]
  (let [points (-> shape :points (gco/transform-points (ctm/modifiers->transform modifiers)))
        has-resize? (not (ctm/only-move? modifiers))

        [modifiers points]
        (let [modifiers
              (cond-> modifiers
                has-resize? (size-pixel-precision shape points precision))

              points
              (if has-resize?
                (-> (:points shape)
                    (gco/transform-points (ctm/modifiers->transform modifiers)) )
                points)]
          [modifiers points])]
    (position-pixel-precision modifiers shape points precision ignore-axis)))

(defn adjust-pixel-precision
  [modif-tree objects precision ignore-axis]
  (let [update-modifiers
        (fn [modif-tree shape]
          (let [modifiers (dm/get-in modif-tree [(:id shape) :modifiers])]
            (cond-> modif-tree
              (and (some? modifiers) (ctm/has-geometry? modifiers))
              (update-in [(:id shape) :modifiers] set-pixel-precision shape precision ignore-axis))))]

    (->> (keys modif-tree)
         (map (d/getf objects))
         (reduce update-modifiers modif-tree))))
