;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.bool
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cph]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.path.bool :as bool]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(defn- create-bool-shape
  [id type name shapes objects]
  (let [shape-id
        (or id (uuid/next))

        head
        (if (= type :difference) (first shapes) (last shapes))

        head
        (cond-> head
          (and (contains? head :svg-attrs) (empty? (:fills head)))
          (assoc :fills path/default-bool-fills))

        shape
        {:id shape-id
         :type :bool
         :bool-type type
         :frame-id (:frame-id head)
         :parent-id (:parent-id head)
         :name name
         :shapes (into [] d/xf:map-id shapes)}

        shape
        (-> shape
            (merge (select-keys head path/bool-style-properties))
            (cts/setup-shape)
            (path/update-bool-shape objects))]

    [shape (cph/get-position-on-parent objects (:id head))]))

(defn- group->bool
  [type group objects]
  (let [shapes (->> (:shapes group)
                    (map (d/getf objects)))
        head (if (= type :difference) (first shapes) (last shapes))
        head (cond-> head
               (and (contains? head :svg-attrs) (empty? (:fills head)))
               (assoc :fills path/default-bool-fills))]
    (-> group
        (assoc :type :bool)
        (assoc :bool-type type)
        (merge (select-keys head bool/style-properties))
        (path/update-bool-shape objects))))

(defn create-bool
  [type & {:keys [ids force-shape-id]}]

  (assert (or (nil? ids) (every? uuid? ids)))

  (ptk/reify ::create-bool-union
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)

            name
            (-> type d/name str/capital)

            ids
            (->> (or ids (dsh/get-selected-ids state))
                 (dsh/process-selected objects))

            xform
            (comp
             (map (d/getf objects))
             (remove cph/frame-shape?)
             (remove ctc/is-variant?)
             (remove #(ctn/has-any-copy-parent? objects %)))

            shapes
            (->> (cph/order-by-indexed-shapes objects ids)
                 (into [] xform)
                 (not-empty))]

        (when shapes
          (let [[shape index]
                (create-bool-shape force-shape-id type name (reverse shapes) objects)

                shape-id
                (get shape :id)

                changes
                (-> (pcb/empty-changes it page-id)
                    (pcb/with-objects objects)
                    (pcb/add-object shape {:index (inc index)})
                    (pcb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
                    (pcb/change-parent shape-id shapes))]

            (rx/of (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set shape-id)))))))))

(defn group-to-bool
  [shape-id type]
  (ptk/reify ::group-to-bool
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects   (dsh/lookup-page-objects state)
            update-fn (partial group->bool type)]
        (when-not (ctn/has-any-copy-parent? objects (get objects shape-id))
          (rx/of (dwsh/update-shapes [shape-id] update-fn {:with-objects? true :reg-objects? true})))))))

(defn- bool->group
  [shape objects]
  (-> shape
      (assoc :type :group)
      (dissoc :bool-type)
      (d/without-keys path/bool-group-style-properties)
      (gsh/update-group-selrect
       (mapv (d/getf objects)
             (:shapes shape)))))

(defn bool-to-group
  [shape-id]
  (ptk/reify ::bool-to-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)]
        (when-not (ctn/has-any-copy-parent? objects (get objects shape-id))
          (rx/of (dwsh/update-shapes [shape-id] bool->group {:with-objects? true :reg-objects? true})))))))

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
