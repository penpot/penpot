;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.layout
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gre]
   [app.common.geom.shapes.transforms :as gtr]))

(defn calc-layout-data
  "Digest the layout data to pass it to the constrains"
  [_parent children transformed-rect]

  (let [[children-width children-height]
        (->> children (reduce (fn [[acc-width acc-height] shape]
                                [(+ acc-width (-> shape :points gre/points->rect :width))
                                 (+ acc-height (-> shape :points gre/points->rect :height))]) [0 0]))]
    {:start-x (:x transformed-rect)
     :start-y (:y transformed-rect)
     :children-width children-width
     :children-height children-height})
  )

(defn calc-layout-modifiers
  [_parent child current-modifier _modifiers _transformed-rect {:keys [start-x start-y] :as layout-data}]

  (let [current-modifier (dissoc current-modifier :displacement-after)
        child' (-> child (assoc :modifiers current-modifier) gtr/transform-shape)
        bounds' (-> child' :points gre/points->selrect)
        corner-p (gpt/point start-x start-y)
        displacement (gmt/translate-matrix (gpt/subtract corner-p (gpt/point bounds')))
        modifiers (-> current-modifier
                      (assoc :displacement-after displacement))

        next-x (+ start-x (:width bounds'))]

    [modifiers (assoc layout-data :start-x next-x )]))
