;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.pixel-precision
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]))

(defn size-pixel-precision
  [modifiers shape]
  (let [{:keys [points transform transform-inverse] :as shape} (gtr/transform-shape shape modifiers)
        origin        (gpo/origin points)
        curr-width    (gpo/width-points points)
        curr-height   (gpo/height-points points)

        path?            (cph/path-shape? shape)
        vertical-line?   (and path? (<= curr-width 0.01))
        horizontal-line? (and path? (<= curr-height 0.01))

        target-width  (if vertical-line? curr-width (max 1 (mth/round curr-width)))
        target-height (if horizontal-line? curr-height (max 1 (mth/round curr-height)))

        ratio-width  (/ target-width curr-width)
        ratio-height (/ target-height curr-height)
        scalev       (gpt/point ratio-width ratio-height)]
    (cond-> modifiers
      (or (not (mth/almost-zero? (- ratio-width 1)))
          (not (mth/almost-zero? (- ratio-height 1))))
      (ctm/set-resize scalev origin transform transform-inverse))))

(defn position-pixel-precision
  [modifiers shape]
  (let [{:keys [points]} (gtr/transform-shape shape modifiers)
        bounds        (gpr/points->rect points)
        corner        (gpt/point bounds)
        target-corner (gpt/round corner)
        deltav        (gpt/to-vec corner target-corner)]
    (cond-> modifiers
      (or (not (mth/almost-zero? (:x deltav)))
          (not (mth/almost-zero? (:y deltav))))
      (ctm/set-move deltav))))

(defn set-pixel-precision
  "Adjust modifiers so they adjust to the pixel grid"
  [modifiers shape]

  (-> modifiers
      (size-pixel-precision shape)
      (position-pixel-precision shape)))
