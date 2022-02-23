;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.focus
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.pages.indices :as cpi]
   [app.common.uuid :as uuid]))

(defn focus-objects
  [objects focus]
  (let [[ids-with-children z-index]
        (when (d/not-empty? focus)
          [(into (conj focus uuid/zero)
                 (mapcat (partial cph/get-children-ids objects))
                 focus)
           (cpi/calculate-z-index objects)])

        sort-by-z-index
        (fn [coll]
          (->> coll (sort-by (fn [a b] (- (get z-index a) (get z-index b))))))]

    (cond-> objects
      (some? ids-with-children)
      (-> (select-keys ids-with-children)
          (assoc-in [uuid/zero :shapes] (sort-by-z-index focus))))))

(defn filter-not-focus
  [objects focus ids]

  (let [focused-ids
        (when (d/not-empty? focus)
          (into focus
                (mapcat (partial cph/get-children-ids objects))
                focus))]

    (if (some? focused-ids)
      (into (d/ordered-set)
            (filter #(contains? focused-ids %))
            ids)
      ids)))

(defn is-in-focus?
  [objects focus id]
  (d/seek
   #(contains? focus %)
   (cph/get-parents-seq objects id)))
