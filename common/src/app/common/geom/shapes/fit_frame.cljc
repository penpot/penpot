;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.fit-frame
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.types.modifiers :as ctm]))

(defn fit-frame-modifiers
  [objects {:keys [id transform transform-inverse selrect points show-content] :as frame}]
  (let [children (cfh/get-immediate-children objects (:id frame))]
    (when (d/not-empty? children)
      (let [ids (cfh/get-children-ids objects id)
            center (gco/shape->center frame)

            transform-inverse (gmt/transform-in center transform-inverse)
            transform         (gmt/transform-in center transform)

            ;; Update the object map with the reverse transform
            tr-objects
            (reduce #(update %1 %2 gtr/apply-transform transform-inverse) objects ids)

            bounds
            (->> children
                 (map #(get tr-objects (:id %)))
                 (map #(gsb/get-object-bounds tr-objects % {:ignore-shadow-margin? show-content
                                                            :ignore-margin? false}))
                 (grc/join-rects))

            new-origin (gpt/transform (gpt/point bounds) transform)
            origin     (first points)
            resize-v   (gpt/point (/ (:width bounds) (:width selrect))
                                  (/ (:height bounds) (:height selrect)))]

        (-> (ctm/empty)
            (ctm/resize-parent resize-v origin transform transform-inverse)
            (ctm/move-parent (gpt/to-vec origin new-origin)))))))
