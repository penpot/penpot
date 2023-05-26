;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pages.indices
  (:require
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]))

(defn generate-child-all-parents-index
  "Creates an index where the key is the shape id and the value is a set
  with all the parents"
  ([objects]
   (generate-child-all-parents-index objects (vals objects)))

  ([objects shapes]
   (let [shape->entry
         (fn [shape]
           [(:id shape) (cph/get-parent-ids objects (:id shape))])]
     (into {} (map shape->entry) shapes))))

(defn create-clip-index
  "Retrieves the mask information for an object"
  [objects parents-index]
  (let [retrieve-clips
        (fn [parents]
          (let [lookup-object (fn [id] (get objects id))
                get-clip-parents
                (fn [shape]
                  (cond-> []
                    (and (= :frame (:type shape))
                         (not (:show-content shape))
                         (not= uuid/zero (:id shape)))
                    (conj shape)

                    (:masked-group? shape)
                    (conj (get objects (->> shape :shapes first)))

                    (= :bool (:type shape))
                    (conj shape)))]

            (into []
                  (comp (map lookup-object)
                        (mapcat get-clip-parents))
                  parents)))]
    (-> parents-index
        (update-vals retrieve-clips))))
