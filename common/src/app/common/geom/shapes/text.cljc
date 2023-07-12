;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.text
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.transforms :as gtr]))

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
