;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.min-size-layout
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.flex-layout.bounds :as fb]
   [app.common.geom.shapes.flex-layout.layout-data :as fd]
   [app.common.geom.shapes.grid-layout.bounds :as gb]
   [app.common.geom.shapes.grid-layout.layout-data :as gd]
   [app.common.geom.shapes.points :as gpo]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]))

(defn child-min-width
  [child child-bounds bounds objects]
  (let [min-width
        (cond
          (and (ctl/fill-width? child)
               (ctl/flex-layout? child))
          (gpo/width-points (fb/layout-content-bounds bounds child (->> child :shapes (map (d/getf objects))) objects))
          
          (and (ctl/fill-width? child)
               (ctl/grid-layout? child))
          (let [children
                (->> (cph/get-immediate-children objects (:id child))
                     (remove :hidden)
                     (remove gco/invalid-geometry?)
                     (map (fn [child] [@(get bounds (:id child)) child])))
                layout-data (gd/calc-layout-data child @(get bounds (:id child)) children bounds objects true)]
            (gpo/width-points (gb/layout-content-bounds bounds child layout-data)))
          
          (ctl/fill-width? child)
          (ctl/child-min-width child)

          :else
          (gpo/width-points child-bounds))]
    (+ min-width (ctl/child-width-margin child))))

(defn child-min-height
  [child child-bounds bounds objects]
  (let [min-height
        (cond
          (and (ctl/fill-height? child) (ctl/flex-layout? child))
          (gpo/height-points (fb/layout-content-bounds bounds child (->> child :shapes (map (d/getf objects))) objects))
          
          (and (ctl/fill-height? child) (ctl/grid-layout? child))
          (let [children (->> child :shapes
                              (map (d/getf objects))
                              (map  (fn [child] [@(get bounds (:id  child)) child])))
                layout-data (gd/calc-layout-data child (:points child) children bounds objects true)
                auto-bounds (gb/layout-content-bounds bounds child layout-data)]
            (gpo/height-points auto-bounds))
          
          (ctl/fill-height? child)
          (ctl/child-min-height child)

          :else
          (gpo/height-points child-bounds))]
    (+ min-height (ctl/child-height-margin child))))

#?(:cljs
   (do (set! fd/-child-min-width child-min-width)
       (set! fd/-child-min-height child-min-height)
       (set! fb/-child-min-width child-min-width)
       (set! fb/-child-min-height child-min-height)
       (set! gd/-child-min-width child-min-width)
       (set! gd/-child-min-height child-min-height))

   :clj
   (do (alter-var-root #'fd/-child-min-width (constantly child-min-width))
       (alter-var-root #'fd/-child-min-height (constantly child-min-height))
       (alter-var-root #'fb/-child-min-width (constantly child-min-width))
       (alter-var-root #'fb/-child-min-height (constantly child-min-height))
       (alter-var-root #'gd/-child-min-width (constantly child-min-width))
       (alter-var-root #'gd/-child-min-height (constantly child-min-height))))

