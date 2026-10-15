;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.override-scan
  "Finds the copy shapes that differ from their mains without an override.
   A copy keeps a value of its own only in a touched group, so a value that
   differs in an untouched group is lost propagation: a sync would change
   it. The scan runs that sync in memory and reports what it would write.
   For debugging real files (`mem:common/component-sync-contract`)."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.logic.libraries :as cll]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape.layout :as ctl]))

(defn- derived-geometry?
  "Whether the geometry of `shape` is not the main's to give: the size of a
   text that grows with its content, or the place of a layout child."
  [objects shape]
  (or (and (cfh/text-shape? shape)
           (not= :fixed (:grow-type shape)))
      (and (ctl/any-layout-immediate-child? objects shape)
           (not (ctl/position-absolute? shape)))))

(defn- change-findings
  "The findings of a change of a sync of the file `data`: for a change of
   attributes, the shape with the attributes and groups it writes; for a
   change of the shape tree, the shape with `:structure`."
  [data {:keys [type page-id id operations parent-id]}]
  (let [objects (get-in data [:pages-index page-id :objects])]
    (case type
      :mod-obj
      (let [shape (get objects id)
            attrs (into #{}
                        (comp (filter #(= :set (:type %)))
                              (map :attr)
                              (remove #{:position-data})
                              (filter #(some? (ctk/resolve-sync-group (:type shape) %)))
                              (remove #(and (= :geometry-group (ctk/resolve-sync-group (:type shape) %))
                                            (derived-geometry? objects shape))))
                        operations)]
        (when (seq attrs)
          [{:page-id page-id
            :id      id
            :name    (:name shape)
            :attrs   attrs
            :groups  (into #{} (map #(ctk/resolve-sync-group (:type shape) %)) attrs)}]))

      (:add-obj :mov-objects)
      (let [parent (get objects parent-id)]
        [{:page-id page-id :id parent-id :name (:name parent) :groups #{:structure}}])

      :del-obj
      (let [shape (get objects id)]
        [{:page-id page-id :id (:parent-id shape) :name (:name (get objects (:parent-id shape)))
          :groups #{:structure}}])

      nil)))

(defn- merge-findings
  [findings]
  (->> findings
       (group-by (juxt :page-id :id))
       (map (fn [[_ items]]
              (reduce (fn [a b]
                        (-> a
                            (update :attrs into (:attrs b))
                            (update :groups into (:groups b))))
                      (update (first items) :attrs set)
                      (rest items))))
       (map #(cond-> % (empty? (:attrs %)) (dissoc :attrs)))))

(defn scan
  "The copy shapes of the pages of `file` that differ from their mains
   without an override, as `{:page-id :id :name :groups :attrs}`, one per
   shape; `:structure` among the groups means children that differ.
   `libraries` are the other files by id. `skip-component?`, given a
   library and a component, leaves out the copies of that component (those
   of a library update not taken yet may differ)."
  [file libraries & {:keys [skip-component?] :or {skip-component? (constantly false)}}]
  (let [file-id   (:id file)
        libraries (assoc libraries file-id file)
        data      (:data file)]
    (->> (vals libraries)
         (mapcat (fn [library]
                   (->> (ctkl/components-seq (:data library))
                        (remove #(skip-component? library %))
                        (map #(vector library %)))))
         (mapcat (fn [[library component]]
                   (:redo-changes
                    (cll/generate-sync-file-changes (pcb/empty-changes)
                                                    nil
                                                    :components
                                                    file-id
                                                    (:id component)
                                                    (:id library)
                                                    libraries
                                                    file-id))))
         (filter :page-id)
         (mapcat #(change-findings data %))
         (merge-findings))))
