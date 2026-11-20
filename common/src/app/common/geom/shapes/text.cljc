;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.geom.shapes.text
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]))

(defn text-size-drifted?
  "True when a measured auto-grow size is off the stored one by more than
  `tolerance` on an axis that grows (width for :auto-width, height for both
  auto-grow types). An axis that fills its layout parent takes its size
  from the layout, so it never drifts. Other grow types never drift."
  [{:keys [grow-type width height] :as shape} new-width new-height tolerance]
  (boolean
   (or (and (= :auto-width grow-type)
            (not (ctl/fill-width? shape))
            (not (mth/close? width new-width tolerance)))
       (and (contains? #{:auto-width :auto-height} grow-type)
            (not (ctl/fill-height? shape))
            (not (mth/close? height new-height tolerance))))))

(defn position-data->rect
  [{:keys [x y width height]}]
  (grc/make-rect x (- y height) width height))

(defn shape->rect
  [shape]
  (let [points (->> (:position-data shape)
                    (mapcat (comp grc/rect->points position-data->rect)))]
    (if (seq points)
      (grc/points->rect points)
      (dm/get-prop shape :selrect))))

(defn shape->bounds
  [shape]
  (let [points (->> (:position-data shape)
                    (mapcat (comp grc/rect->points position-data->rect)))]
    (-> points
        (gco/transform-points (gtr/transform-matrix shape))
        (grc/points->rect))))

(defn overlaps-position-data?
  "Checks if the given position data is inside the shape"
  [{:keys [points]} position-data]
  (let [bounding-box (grc/points->rect points)
        fix-rect #(assoc % :y (- (:y %) (:height %)))]
    (->> position-data
         (some #(grc/overlaps-rects? bounding-box (fix-rect %)))
         (boolean))))
