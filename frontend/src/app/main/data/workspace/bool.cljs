;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.bool
  (:require
   [app.common.data :as d]
   [app.common.logic.bool :as clb]
   [app.common.types.container :as ctn]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn create-bool
  [type & {:keys [ids force-shape-id]}]

  (assert (or (nil? ids) (every? uuid? ids)))

  (ptk/reify ::create-bool-union
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            ids     (->> (or ids (dsh/get-selected-ids state))
                         (dsh/process-selected objects))]
        (when-let [[shape changes] (clb/generate-create-bool it page-id objects ids type force-shape-id)]
          (rx/of (dch/commit-changes changes)
                 (dws/select-shapes (d/ordered-set (:id shape)))))))))

(defn group-to-bool
  [shape-id type]
  (ptk/reify ::group-to-bool
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects   (dsh/lookup-page-objects state)
            update-fn (partial clb/group->bool type)]
        (when-not (ctn/has-any-copy-parent? objects (get objects shape-id))
          (rx/of (dwsh/update-shapes [shape-id] update-fn {:with-objects? true :reg-objects? true})))))))

(defn bool-to-group
  [shape-id]
  (ptk/reify ::bool-to-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)]
        (when-not (ctn/has-any-copy-parent? objects (get objects shape-id))
          (rx/of (dwsh/update-shapes [shape-id] clb/bool->group {:with-objects? true :reg-objects? true})))))))

(defn change-bool-type
  [shape-id type]
  (ptk/reify ::change-bool-type
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            change-type
            (fn [shape] (assoc shape :bool-type type))]
        (when-not (ctn/has-any-copy-parent? objects (get objects shape-id))
          (rx/of (dwsh/update-shapes [shape-id] change-type {:reg-objects? true})))))))
