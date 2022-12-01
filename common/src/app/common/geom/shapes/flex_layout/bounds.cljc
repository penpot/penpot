;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.bounds
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]))

(defn child-layout-bound-points
  "Returns the bounds of the children as points"
  [parent child parent-bounds child-bounds]

  (let [row? (ctl/row? parent)
        col? (ctl/col? parent)

        hv   (partial gpo/start-hv parent-bounds)
        vv   (partial gpo/start-vv parent-bounds)

        v-start? (ctl/v-start? parent)
        v-center? (ctl/v-center? parent)
        v-end? (ctl/v-end? parent)
        h-start? (ctl/h-start? parent)
        h-center? (ctl/h-center? parent)
        h-end? (ctl/h-end? parent)

        base-p (gpo/origin child-bounds)

        width (gpo/width-points child-bounds)
        height (gpo/height-points child-bounds)

        min-width (if (ctl/fill-width? child)
                    (ctl/child-min-width child)
                    width)

        min-height (if (ctl/fill-height? child)
                     (ctl/child-min-height child)
                     height)

        ;; This is the leftmost (when row) or topmost (when col) point
        ;; Will be added always to the bounds and then calculated the other limits
        ;; from there
        base-p (cond-> base-p
                 (and row? v-center?)
                 (gpt/add (vv (/ height 2)))

                 (and row? v-end?)
                 (gpt/add (vv height))

                 (and col? h-center?)
                 (gpt/add (hv (/ width 2)))

                 (and col? h-end?)
                 (gpt/add (hv width)))]

    (cond-> [base-p]
      (and (mth/almost-zero? min-width) (mth/almost-zero? min-height))
      (conj (cond-> base-p
              row?
              (gpt/add (hv width))

              col?
              (gpt/add (vv height))))

      (not (mth/almost-zero? min-width))
      (conj (cond-> base-p
              (or row? h-start?)
              (gpt/add (hv min-width))

              (and col? h-center?)
              (gpt/add (hv (/ min-width 2)))

              (and col? h-center?)
              (gpt/subtract (hv min-width))))

      (not (mth/almost-zero? min-height))
      (conj (cond-> base-p
              (or col? v-start?)
              (gpt/add (vv min-height))

              (and row? v-center?)
              (gpt/add (vv (/ min-height 2)))

              (and row? v-end?)
              (gpt/subtract (vv min-height)))))))

(defn layout-content-bounds
  [bounds {:keys [layout-padding] :as parent} children]

  (let [parent-id (:id parent)
        parent-bounds @(get bounds parent-id)

        {pad-top :p1 pad-right :p2 pad-bottom :p3 pad-left :p4} layout-padding
        pad-top    (or pad-top 0)
        pad-right  (or pad-right 0)
        pad-bottom (or pad-bottom 0)
        pad-left   (or pad-left 0)

        child-bounds
        (fn [child]
          (let [child-id (:id child)
                child-bounds  @(get bounds child-id)
                child-bounds
                (if (or (ctl/fill-height? child) (ctl/fill-height? child))
                  (child-layout-bound-points parent child parent-bounds child-bounds)
                  child-bounds)]
            (gpo/parent-coords-bounds child-bounds parent-bounds)))]

    (as-> children $
      (map child-bounds $)
      (gpo/merge-parent-coords-bounds $ parent-bounds)
      (gpo/pad-points $ (- pad-top) (- pad-right) (- pad-bottom) (- pad-left)))))

