;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.geom.modif-tree
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.modifiers :as ctm]))

(defn- combine-modifiers
  "`modifiers` added after `old-modifiers`, which may be nil."
  [old-modifiers modifiers]
  (if (nil? old-modifiers)
    modifiers
    (ctm/add-modifiers old-modifiers modifiers)))

(defn add-modifiers
  "Add the given modifiers to the map of modifiers."
  [modif-tree id modifiers]
  (if (ctm/empty? modifiers)
    modif-tree
    (let [new-modifiers
          (combine-modifiers (dm/get-in modif-tree [id :modifiers]) modifiers)]
      (if (ctm/empty? new-modifiers)
        (dissoc modif-tree id)
        (assoc-in modif-tree [id :modifiers] new-modifiers)))))

(defn merge-modif-tree
  "Merge two maps of modifiers into a single one"
  [modif-tree other-tree]
  (if (empty? other-tree)
    modif-tree
    (-> (reduce
         (fn [tree [id {:keys [modifiers]}]]
           (if (ctm/empty? modifiers)
             tree
             (let [entry         (get tree id)
                   new-modifiers (combine-modifiers (:modifiers entry) modifiers)]
               (if (ctm/empty? new-modifiers)
                 (dissoc! tree id)
                 (assoc! tree id (assoc entry :modifiers new-modifiers))))))
         (transient (or modif-tree {}))
         other-tree)
        (persistent!)
        (with-meta (meta modif-tree)))))

(defn apply-structure-modifiers
  "Only applies the structure modifiers to the objects tree map"
  [objects modif-tree]
  (letfn [(update-children-structure-modifiers
            [objects ids modifiers]
            (reduce #(update %1 %2 ctm/apply-structure-modifiers modifiers) objects ids))

          (apply-shape [objects [id {:keys [modifiers]}]]
            (cond-> objects
              (ctm/has-structure? modifiers)
              (update id ctm/apply-structure-modifiers modifiers)

              (and (ctm/has-structure? modifiers)
                   (ctm/has-structure-child? modifiers))
              (update-children-structure-modifiers
               (cfh/get-children-ids objects id)
               (ctm/select-child-structre-modifiers modifiers))))]
    (reduce apply-shape objects modif-tree)))
