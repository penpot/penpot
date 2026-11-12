;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.bool
  "The changes of the boolean actions."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.path.bool :as bool]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(defn- create-bool-shape
  [id type name shapes objects]
  (let [shape-id
        (or id (uuid/next))

        head
        (if (= type :difference) (first shapes) (last shapes))

        head
        (cond-> head
          (and (contains? head :svg-attrs) (empty? (:fills head)))
          (assoc :fills (path/get-default-bool-fills)))

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

    [shape (cfh/get-position-on-parent objects (:id head))]))

(defn group->bool
  [type group objects]
  (let [shapes (->> (:shapes group)
                    (map (d/getf objects)))
        head (if (= type :difference) (first shapes) (last shapes))
        head (cond-> head
               (and (contains? head :svg-attrs) (empty? (:fills head)))
               (assoc :fills (path/get-default-bool-fills)))]
    (-> group
        (assoc :type :bool)
        (assoc :bool-type type)
        (merge (select-keys head bool/style-properties))
        (path/update-bool-shape objects))))

(defn bool->group
  [shape objects]
  (-> shape
      (assoc :type :group)
      (dissoc :bool-type)
      (d/without-keys path/bool-group-style-properties)
      (gsh/update-group-selrect
       (mapv (d/getf objects)
             (:shapes shape)))))

(defn generate-create-bool
  "The bool of `type` that combines the shapes of `ids`, and the changes
   that create it; nil when none of them can take part: boards, variants
   and shapes inside a copy do not."
  [it page-id objects ids type force-shape-id]
  (let [name
        (-> type d/name str/capital)

        xform
        (comp
         (map (d/getf objects))
         (remove cfh/frame-shape?)
         (remove ctk/is-variant?)
         (remove #(ctn/has-any-copy-parent? objects %)))

        shapes
        (->> (cfh/order-by-indexed-shapes objects ids)
             (into [] xform)
             (not-empty))]

    (when shapes
      (let [[shape index]
            (create-bool-shape force-shape-id type name (reverse shapes) objects)

            shape-id
            (get shape :id)

            ;; the bool takes the grid cell of the shape whose place it takes
            head-id
            (:id (if (= type :difference) (last shapes) (first shapes)))

            parent-id
            (:parent-id shape)

            target-cell
            (when (ctl/grid-layout? objects parent-id)
              (ctl/get-cell-by-shape-id (get objects parent-id) head-id))

            grid-parents
            (into #{} (comp (map :parent-id) (filter (partial ctl/grid-layout? objects))) shapes)]
        [shape
         (-> (pcb/empty-changes it page-id)
             (pcb/with-objects objects)
             (pcb/add-object shape {:index (inc index)})
             ;; as a group's, the shapes of a bool scale with it
             (pcb/update-shapes (map :id shapes)
                                (fn [shape]
                                  (-> shape
                                      (d/assoc-when :constraints-h :scale)
                                      (d/assoc-when :constraints-v :scale))))
             (pcb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
             (pcb/change-parent shape-id shapes)
             (cond-> target-cell
               (pcb/update-shapes
                [parent-id]
                (fn [parent]
                  (assoc-in parent [:layout-grid-cells (:id target-cell) :shapes] [shape-id]))))
             (pcb/update-shapes grid-parents ctl/assign-cells {:with-objects? true}))]))))
