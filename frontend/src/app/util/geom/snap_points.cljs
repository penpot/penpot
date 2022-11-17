;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.geom.snap-points
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape-tree :as ctst]))

(defn selrect-snap-points [{:keys [x y width height] :as selrect}]
  #{(gpt/point x y)
    (gpt/point (+ x width) y)
    (gpt/point (+ x width) (+ y height))
    (gpt/point x (+ y height))
    (gsh/center-selrect selrect)})

(defn frame-snap-points [{:keys [x y width height blocked hidden] :as selrect}]
  (when (and (not blocked) (not hidden))
    (into (selrect-snap-points selrect)
          #{(gpt/point (+ x (/ width 2)) y)
            (gpt/point (+ x width) (+ y (/ height 2)))
            (gpt/point (+ x (/ width 2)) (+ y height))
            (gpt/point x (+ y (/ height 2)))})))

(defn shape-snap-points
  [{:keys [hidden blocked] :as shape}]
  (when (and (not blocked) (not hidden))
    (case (:type shape)
      :frame (-> shape :points gsh/points->selrect frame-snap-points)
      (into #{(gsh/center-shape shape)} (:points shape)))))

(defn guide-snap-points
  [guide frame]

  (cond
    (and (some? frame)
         (not (ctst/rotated-frame? frame))
         (not (cph/root-frame? frame)))
    #{}

    (= :x (:axis guide))
    #{(gpt/point (:position guide) 0)}

    :else
    #{(gpt/point 0 (:position guide))}))
