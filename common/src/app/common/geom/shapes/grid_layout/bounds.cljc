;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.geom.shapes.grid-layout.bounds
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.shape.layout :as ctl]))

(defn layout-content-points
  [bounds parent {:keys [row-tracks column-tracks]}]
  (let [parent-id     (:id parent)
        parent-bounds (get bounds parent-id)]
    (when-let [parent-bounds (some-> parent-bounds deref)]
      (let [hv #(gpo/start-hv parent-bounds %)
            vv #(gpo/start-vv parent-bounds %)]
        (d/concat-vec
         (->> row-tracks
              (mapcat #(vector (:start-p %)
                               (gpt/add (:start-p %) (vv (:size %))))))
         (->> column-tracks
              (mapcat #(vector (:start-p %)
                               (gpt/add (:start-p %) (hv (:size %)))))))))))

(defn layout-content-bounds
  [bounds parent layout-data]

  (let [parent-id     (:id parent)
        parent-bounds (get bounds parent-id)]
    (when-let [parent-bounds (some-> parent-bounds deref)]
      (let [;; the paddings the layout uses: a simple type repeats top and right
            [pad-top pad-right pad-bottom pad-left] (ctl/paddings parent)

            layout-points (layout-content-points bounds parent layout-data)]

        (if (d/not-empty? layout-points)
          (-> layout-points
              (gpo/merge-parent-coords-bounds parent-bounds)
              (gpo/pad-points (- pad-top) (- pad-right) (- pad-bottom) (- pad-left)))
          ;; Cannot create some bounds from the children so we return the parent's
          parent-bounds)))))
