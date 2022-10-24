;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.positions
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.shape.layout :as ctl]))

(defn get-child-position
  "Calculates the position for the current shape given the layout-data context"
  [parent
   child-width child-height
   {:keys [start-p layout-gap-row layout-gap-col margin-x margin-y] :as layout-data}]

  (let [row?      (ctl/row? parent)
        col?      (ctl/col? parent)

        h-center? (ctl/h-center? parent)
        h-end?    (ctl/h-end? parent)
        v-center? (ctl/v-center? parent)
        v-end?    (ctl/v-end? parent)
        points    (:points parent)

        hv (partial gpo/start-hv points)
        vv (partial gpo/start-vv points)

        corner-p
        (cond-> start-p
          (and col? h-center?)
          (gpt/add (hv (- (/ child-width 2))))

          (and col? h-end?)
          (gpt/add (hv (- child-width)))

          (and row? v-center?)
          (gpt/add (vv (- (/ child-height 2))))

          (and row? v-end?)
          (gpt/add (vv (- child-height)))

          (some? margin-x)
          (gpt/add (hv margin-x))

          (some? margin-y)
          (gpt/add (vv margin-y)))

        next-p
        (cond-> start-p
          row?
          (gpt/add (hv (+ child-width layout-gap-row)))

          col?
          (gpt/add (vv (+ child-height layout-gap-col)))

          (some? margin-x)
          (gpt/add (hv margin-x))

          (some? margin-y)
          (gpt/add (vv margin-y)))

        layout-data
        (assoc layout-data :start-p next-p)]

    [corner-p layout-data]))
