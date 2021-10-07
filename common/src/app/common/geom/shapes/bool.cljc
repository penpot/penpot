;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.bool
  (:require
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.path.bool :as pb]
   [app.common.path.shapes-to-path :as stp]))

(defn update-bool-selrect
  "Calculates the selrect+points for the boolean shape"
  [shape children objects]

  (let [content (->> children
                     (map #(stp/convert-to-path % objects))
                     (mapv :content)
                     (pb/content-bool (:bool-type shape)))

        [points selrect]
        (if (empty? content)
          (let [selrect (gtr/selection-rect children)
                points (gpr/rect->points selrect)]
            [points selrect])
          (gsp/content->points+selrect shape content))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))
