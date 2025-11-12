;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.indices
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]))

(defn- generate-index
  "An optimized algorithm for calculate parents index that walk from top
  to down starting from a provided shape-id. Usefull when you want to
  create an index for the whole objects or subpart of the tree."
  [index objects shape-id parents]
  (let [shape   (get objects shape-id)
        index   (assoc index shape-id parents)
        parents (cons shape-id parents)]
    (reduce (fn [index shape-id]
              (generate-index index objects shape-id parents))
            index
            (:shapes shape))))

(defn generate-child-all-parents-index
  "Creates an index where the key is the shape id and the value is a set
  with all the parents"
  ([objects]
   (generate-index {} objects uuid/zero []))

  ([objects shapes]
   (let [shape->entry
         (fn [shape]
           [(:id shape) (cfh/get-parent-ids objects (:id shape))])]
     (into {} (map shape->entry) shapes))))

(defn create-clip-index
  "Retrieves the mask information for an object"
  [objects parents-index]
  (let [get-clip-parents
        (fn [shape]
          (let [shape-id (dm/get-prop shape :id)]
            (cond-> []
              (or (and (cfh/frame-shape? shape)
                       (not (:show-content shape))
                       (not= uuid/zero shape-id))
                  (cfh/bool-shape? shape))
              (conj shape)

              (:masked-group shape)
              (conj (get objects (->> shape :shapes first))))))

        xform
        (comp (map (d/getf objects))
              (mapcat get-clip-parents))

        populate-with-clips
        (fn [parents]
          (into [] xform parents))]

    (d/update-vals parents-index populate-with-clips)))
