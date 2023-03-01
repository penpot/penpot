;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.bounds
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
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

        fill-w? (ctl/fill-width? child)
        fill-h? (ctl/fill-height? child)

        base-p (gpo/origin child-bounds)

        width (gpo/width-points child-bounds)
        height (gpo/height-points child-bounds)

        min-width (if fill-w?
                    (ctl/child-min-width child)
                    width)

        min-height (if fill-h?
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
                 (gpt/add (hv width)))

        ;; We need some height/width to calculate the bounds. We stablish the minimum
        min-width (max min-width 0.01)
        min-height (max min-height 0.01)]

    (cond-> [base-p
             (gpt/add base-p (hv 0.01))
             (gpt/add base-p (vv 0.01))]

      col?
      (conj (gpt/add base-p (vv min-height)))

      row?
      (conj (gpt/add base-p (hv min-width)))

      (and col? h-start?)
      (conj (gpt/add base-p (hv min-width)))

      (and col? h-center?)
      (conj (gpt/add base-p (hv (/ min-width 2)))
            (gpt/subtract base-p (hv (/ min-width 2))))

      (and col? h-end?)
      (conj (gpt/subtract base-p (hv min-width)))

      (and row? v-start?)
      (conj (gpt/add base-p (vv min-height)))

      (and row? v-center?)
      (conj (gpt/add base-p (vv (/ min-height 2)))
            (gpt/subtract base-p (vv (/ min-height 2))))

      (and row? v-end?)
      (conj (gpt/subtract base-p (vv min-height))))))

(defn layout-content-points
  [bounds parent children]

  (let [parent-id (:id parent)
        parent-bounds @(get bounds parent-id)
        get-child-bounds
        (fn [child]
          (let [child-id (:id child)
                child-bounds  @(get bounds child-id)
                [margin-top margin-right margin-bottom margin-left] (ctl/child-margins child)

                child-bounds
                (if (or (ctl/fill-width? child) (ctl/fill-height? child))
                  (child-layout-bound-points parent child parent-bounds child-bounds)
                  child-bounds)

                child-bounds
                (when (d/not-empty? child-bounds)
                  (-> (gpo/parent-coords-bounds child-bounds parent-bounds)
                      (gpo/pad-points (- margin-top) (- margin-right) (- margin-bottom) (- margin-left))))]

            child-bounds))]

    (->> children
         (remove ctl/layout-absolute?)
         (map get-child-bounds))))

(defn layout-content-bounds
  [bounds {:keys [layout-padding] :as parent} children]

  (let [parent-id (:id parent)
        parent-bounds @(get bounds parent-id)

        row?            (ctl/row? parent)
        col?            (ctl/col? parent)
        space-around?   (ctl/space-around? parent)
        space-evenly?   (ctl/space-evenly? parent)
        content-evenly? (ctl/content-evenly? parent)
        [layout-gap-row layout-gap-col] (ctl/gaps parent)

        row-pad (if (or (and col? space-evenly?)
                        (and col? space-around?)
                        (and row? content-evenly?))
                  layout-gap-row
                  0)

        col-pad (if (or(and row? space-evenly?)
                       (and row? space-around?)
                       (and col? content-evenly?))
                  layout-gap-col
                  0)

        {pad-top :p1 pad-right :p2 pad-bottom :p3 pad-left :p4} layout-padding
        pad-top    (+ (or pad-top 0) row-pad)
        pad-right  (+ (or pad-right 0) col-pad)
        pad-bottom (+ (or pad-bottom 0) row-pad)
        pad-left   (+ (or pad-left 0) col-pad)

        layout-points
        (layout-content-points bounds parent children)]

    (if (d/not-empty? layout-points)
      (-> layout-points
          (gpo/merge-parent-coords-bounds parent-bounds)
          (gpo/pad-points (- pad-top) (- pad-right) (- pad-bottom) (- pad-left)))
      ;; Cannot create some bounds from the children so we return the parent's
      parent-bounds)))
