;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pages.focus
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape-tree :as ctt]
   [app.common.uuid :as uuid]))

(defn focus-objects
  [objects focus]
  (let [ids-with-children
        (when (d/not-empty? focus)
          (into (conj focus uuid/zero)
                (mapcat (partial cph/get-children-ids objects))
                focus))]

    (cond-> objects
      (some? ids-with-children)
      (-> (select-keys ids-with-children)
          (assoc-in [uuid/zero :shapes] (ctt/sort-z-index objects focus))))))

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
  (d/seek (partial contains? focus)
          (cons id (cph/get-parent-ids objects id))))
