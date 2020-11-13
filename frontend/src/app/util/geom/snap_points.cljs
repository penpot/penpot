;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.geom.snap-points
  (:require
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.point :as gpt]))

(defn- selrect-snap-points [{:keys [x y width height]}]
  #{(gpt/point x y)
    (gpt/point (+ x width) y)
    (gpt/point (+ x width) (+ y height))
    (gpt/point x (+ y height))})

(defn- frame-snap-points [{:keys [x y width height] :as selrect}]
  (into (selrect-snap-points selrect)
        #{(gpt/point (+ x (/ width 2)) y)
          (gpt/point (+ x width) (+ y (/ height 2)))
          (gpt/point (+ x (/ width 2)) (+ y height))
          (gpt/point x (+ y (/ height 2)))}))

(defn shape-snap-points
  [shape]
  (let [shape (gsh/transform-shape shape)]
    (case (:type shape)
      :frame (-> shape :selrect frame-snap-points)
      (:path :curve) (-> shape :selrect selrect-snap-points)
      (into #{(gsh/center-shape shape)} (:points shape)))
    ))
