;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.swap
  "Swap interaction: replace one shape's appearance with another in view mode."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.types.modifiers :as ctm]))

(defn- shape-for-slot
  "Use `dest` geometry and interactions, aligned to `source` top-left."
  [source dest]
  (let [src-sr (:selrect source)
        dst-sr (:selrect dest)
        delta  (gpt/subtract (gpt/point (:x1 src-sr) (:y1 src-sr))
                             (gpt/point (:x1 dst-sr) (:y1 dst-sr)))
        moved  (gsh/transform-shape dest (ctm/move-modifiers delta))]
    (-> moved
        (assoc :id (:id source)
               :frame-id (:frame-id source)
               :parent-id (:parent-id source)
               :interactions (:interactions dest)))))

(defn apply-viewer-shape-swaps
  "Apply active swap replacements for prototype view. Shapes used only as swap
  targets are tagged with :viewer-swap-hidden so they are not drawn twice."
  [objects swaps]
  (if (empty? swaps)
    objects
    (reduce (fn [objs [source-id dest-id]]
              (let [src (get objs source-id)
                    dst (get objs dest-id)]
                (if (and src dst (not (:hidden src)))
                  (-> objs
                      (assoc source-id (shape-for-slot src dst))
                      (assoc dest-id (assoc dst :viewer-swap-hidden true)))
                  objs)))
            objects swaps)))
