;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.bool
  (:require
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.path.bool :as pb]
   [app.common.path.shapes-to-path :as stp]))

(defn update-bool-selrect
  [shape children objects]

  (let [selrect (->> children
                     (map #(stp/convert-to-path % objects))
                     (mapv :content)
                     (pb/content-bool (:bool-type shape))
                     (gsp/content->selrect))
        points (gpr/rect->points selrect)]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))
