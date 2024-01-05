;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.bounds
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.shape.layout :as ctl]))

;; Setted in app.common.geom.shapes.common-layout
;; We do it this way because circular dependencies
(def -child-min-width nil)

(defn child-min-width
  [child child-bounds bounds objects]
  (-child-min-width child child-bounds bounds objects))

(def -child-min-height nil)

(defn child-min-height
  [child child-bounds bounds objects]
  (-child-min-height child child-bounds bounds objects))

(defn child-layout-bound-points
  "Returns the bounds of the children as points"
  ([parent child parent-bounds child-bounds bounds objects]
   (child-layout-bound-points parent child parent-bounds child-bounds (gpt/point) bounds objects))

  ([parent child parent-bounds child-bounds correct-v bounds objects]
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

         min-width (child-min-width child child-bounds bounds objects)
         min-height (child-min-height child child-bounds bounds objects)

         ;; This is the leftmost (when row) or topmost (when col) point
         ;; Will be added always to the bounds and then calculated the other limits
         ;; from there
         base-p
         (cond-> base-p
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
         min-height (max min-height 0.01)

         base-p (gpt/add base-p correct-v)

         result
         [base-p
          (gpt/add base-p (hv 0.01))
          (gpt/add base-p (vv 0.01))]

         result
         (cond-> result
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
           (conj (gpt/subtract base-p (vv min-height))))

         correct-v
         (cond-> correct-v
           (and row? (ctl/fill-width? child))
           (gpt/subtract (hv (+ width min-width)))

           (and col? (ctl/fill-height? child))
           (gpt/subtract (vv (+ height min-height))))]
     [result correct-v])))

(defn layout-content-points
  [bounds parent children objects]

  (let [parent-id     (dm/get-prop parent :id)
        parent-bounds @(get bounds parent-id)
        reverse?      (ctl/reverse? parent)
        children      (cond->> children (not reverse?) reverse)]

    (loop [children  (seq children)
           result    (transient [])
           correct-v (gpt/point 0)]

      (if (not children)
        (persistent! result)

        (let [child (first children)
              child-id (dm/get-prop child :id)
              child-bounds @(get bounds child-id)
              [margin-top margin-right margin-bottom margin-left] (ctl/child-margins child)

              [child-bounds correct-v]
              (if (or (ctl/fill-width? child) (ctl/fill-height? child))
                (child-layout-bound-points parent child parent-bounds child-bounds correct-v bounds objects)
                [(->> child-bounds (map #(gpt/add % correct-v))) correct-v])

              child-bounds
              (when (d/not-empty? child-bounds)
                (-> (gpo/parent-coords-bounds child-bounds parent-bounds)
                    (gpo/pad-points (- margin-top) (- margin-right) (- margin-bottom) (- margin-left))))]

          (recur (next children)
                 (cond-> result (some? child-bounds) (conj! child-bounds))
                 correct-v))))))

(defn layout-content-bounds
  [bounds {:keys [layout-padding] :as parent} children objects]

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

        col-pad (if (or (and row? space-evenly?)
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
        (layout-content-points bounds parent children objects)]

    (if (d/not-empty? layout-points)
      (-> layout-points
          (gpo/merge-parent-coords-bounds parent-bounds)
          (gpo/pad-points (- pad-top) (- pad-right) (- pad-bottom) (- pad-left)))
      ;; Cannot create some bounds from the children so we return the parent's
      parent-bounds)))
