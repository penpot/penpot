;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.snap
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape-tree :as ctst]))

(defn rect->snap-points
  [rect]
  (let [x (dm/get-prop rect :x)
        y (dm/get-prop rect :y)
        w (dm/get-prop rect :width)
        h (dm/get-prop rect :height)]
    #{(gpt/point x y)
      (gpt/point (+ x w) y)
      (gpt/point (+ x w) (+ y h))
      (gpt/point x (+ y h))
      (grc/rect->center rect)}))

(defn- frame->snap-points
  [frame]
  (let [points (dm/get-prop frame :points)
        rect   (grc/points->rect points)
        x      (dm/get-prop rect :x)
        y      (dm/get-prop rect :y)
        w      (dm/get-prop rect :width)
        h      (dm/get-prop rect :height)]
    (into (rect->snap-points rect)
          #{(gpt/point (+ x (/ w 2)) y)
            (gpt/point (+ x w) (+ y (/ h 2)))
            (gpt/point (+ x (/ w 2)) (+ y h))
            (gpt/point x (+ y (/ h 2)))})))

(defn shape->snap-points
  [shape]
  (if ^boolean (cph/frame-shape? shape)
    (frame->snap-points shape)
    (->> (dm/get-prop shape :points)
         (into #{(gsh/shape->center shape)}))))

(defn guide->snap-points
  [guide frame]
  (cond
    (and (some? frame)
         (not ^boolean (ctst/rotated-frame? frame))
         (not ^boolean (cph/root-frame? frame)))
    #{}

    (= :x (:axis guide))
    #{(gpt/point (:position guide) 0)}

    :else
    #{(gpt/point 0 (:position guide))}))
