;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.drop-area
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.flex-layout.lines :as fli]
   [app.common.geom.shapes.rect :as gsr]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]))

(defn layout-drop-areas
  "Retrieve the layout drop areas to move shapes inside layouts"
  [{:keys [margin-x margin-y] :as frame} layout-data children]

  (let [col?      (ctl/col? frame)
        row?      (ctl/row? frame)
        h-center? (and row? (ctl/h-center? frame))
        h-end?    (and row? (ctl/h-end? frame))
        v-center? (and col? (ctl/v-center? frame))
        v-end?    (and row? (ctl/v-end? frame))
        reverse?  (:reverse? layout-data)

        [layout-gap-row layout-gap-col] (ctl/gaps frame)

        children (vec (cond->> (d/enumerate children)
                        reverse? reverse))

        redfn-child
        (fn [[result parent-rect prev-x prev-y] [[index child] next]]
          (let [prev-x (or prev-x (:x parent-rect))
                prev-y (or prev-y (:y parent-rect))

                last? (nil? next)

                start-p    (gpt/point (:selrect child))
                start-p    (-> start-p
                               (gmt/transform-point-center (gco/center-shape child) (:transform frame))
                               (gmt/transform-point-center (gco/center-shape frame) (:transform-inverse frame)))

                box-x      (:x start-p)
                box-y      (:y start-p)
                box-width  (-> child :selrect :width)
                box-height (-> child :selrect :height)

                x (if col? (:x parent-rect) prev-x)
                y (if row? (:y parent-rect) prev-y)

                width (cond
                        (and row? last?)
                        (- (+ (:x parent-rect) (:width parent-rect)) x)

                        col?
                        (:width parent-rect)

                        :else
                        (+ box-width (- box-x prev-x) (/ layout-gap-row 2)))

                height (cond
                         (and col? last?)
                         (- (+ (:y parent-rect) (:height parent-rect)) y)

                         row?
                         (:height parent-rect)

                         :else
                         (+ box-height (- box-y prev-y) (/ layout-gap-col 2)))

                [line-area-1 line-area-2]
                (if row?
                  (let [half-point-width (+ (- box-x x) (/ box-width 2))]
                    [(-> (gsr/make-rect x y half-point-width height)
                         (assoc :index (if reverse? (inc index) index)))
                     (-> (gsr/make-rect (+ x half-point-width) y (- width half-point-width) height)
                         (assoc :index (if reverse? index (inc index))))])
                  (let [half-point-height (+ (- box-y y) (/ box-height 2))]
                    [(-> (gsr/make-rect x y width half-point-height)
                         (assoc :index (if reverse? (inc index) index)))
                     (-> (gsr/make-rect x (+ y half-point-height) width (- height half-point-height))
                         (assoc :index (if reverse? index (inc index))))]))

                result (conj result line-area-1 line-area-2)]

            [result parent-rect (+ x width) (+ y height)]))

        redfn-lines
        (fn [[result from-idx prev-x prev-y] [{:keys [start-p layout-gap-row layout-gap-col num-children line-width line-height]} next]]
          (let [start-p (gmt/transform-point-center start-p (gco/center-shape frame) (:transform-inverse frame))

                prev-x (or prev-x (:x frame))
                prev-y (or prev-y (:y frame))
                last? (nil? next)

                line-width
                (if row?
                  (:width frame)
                  (+ line-width margin-x
                     (if row? (* layout-gap-row (dec num-children)) 0)))

                line-height
                (if col?
                  (:height frame)
                  (+ line-height margin-y
                     (if col?
                       (* layout-gap-col (dec num-children))
                       0)))

                box-x
                (- (:x start-p)
                   (cond
                     h-center? (/ line-width 2)
                     h-end? line-width
                     :else 0))

                box-y
                (- (:y start-p)
                   (cond
                     v-center? (/ line-height 2)
                     v-end? line-height
                     :else 0))

                x (if row? (:x frame) prev-x)
                y (if col? (:y frame) prev-y)

                width (cond
                        (and col? last?)
                        (- (+ (:x frame) (:width frame)) x)

                        row?
                        (:width frame)

                        :else
                        (+ line-width (- box-x prev-x) (/ layout-gap-row 2)))

                height (cond
                         (and row? last?)
                         (- (+ (:y frame) (:height frame)) y)

                         col?
                         (:height frame)

                         :else
                         (+ line-height (- box-y prev-y) (/ layout-gap-col 2)))

                line-area (gsr/make-rect x y width height)

                children (subvec children from-idx (+ from-idx num-children))

                result (first (reduce redfn-child [result line-area] (d/with-next children)))]

            [result (+ from-idx num-children) (+ x width) (+ y height)]))]

    (first (reduce redfn-lines [[] 0] (d/with-next (:layout-lines layout-data))))))

(defn get-drop-index
  [frame-id objects position]
  (let [frame       (get objects frame-id)
        position    (gmt/transform-point-center position (gco/center-shape frame) (:transform-inverse frame))
        children    (cph/get-immediate-children objects frame-id)
        layout-data (fli/calc-layout-data frame children)
        drop-areas  (layout-drop-areas frame layout-data children)
        area        (d/seek #(gsr/contains-point? % position) drop-areas)]
    (:index area)))
