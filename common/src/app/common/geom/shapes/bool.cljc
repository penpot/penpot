;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.bool
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.path.bool :as pb]
   [app.common.path.shapes-to-path :as stp]))

(defn calc-bool-content
  [shape objects]

  (let [extract-content-xf
        (comp (map (d/getf objects))
              (filter (comp not :hidden))
              (map #(stp/convert-to-path % objects))
              (map :content))

        shapes-content
        (into [] extract-content-xf (:shapes shape))]
    (pb/content-bool (:bool-type shape) shapes-content)))

(defn update-bool-selrect
  "Calculates the selrect+points for the boolean shape"
  [shape children objects]

  (let [bool-content     (calc-bool-content shape objects)
        shape            (assoc shape :bool-content bool-content)
        [points selrect] (gsp/content->points+selrect shape bool-content)]

    (if (and (some? selrect) (d/not-empty? points))
      (-> shape
          (assoc :selrect selrect)
          (assoc :points points))
      (gtr/update-group-selrect shape children))))

