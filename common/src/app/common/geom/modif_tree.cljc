;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.modif-tree
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.min-size-layout]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]))

(defn add-modifiers
  [modif-tree id modifiers]
  (if (ctm/empty? modifiers)
    modif-tree
    (let [old-modifiers
          (dm/get-in modif-tree [id :modifiers])
          new-modifiers
          (ctm/add-modifiers old-modifiers modifiers)]
      (cond-> modif-tree
        (ctm/empty? new-modifiers)
        (dissoc id)
        
        (not (ctm/empty? new-modifiers))
        (assoc-in [id :modifiers] new-modifiers)))))

(defn merge-modif-tree
  [modif-tree other-tree]
  (reduce
   (fn [modif-tree [id {:keys [modifiers]}]]
     (add-modifiers modif-tree id modifiers))
   modif-tree
   other-tree))

(defn apply-structure-modifiers
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
               (cph/get-children-ids objects id)
               (ctm/select-child-structre-modifiers modifiers))))]
    (reduce apply-shape objects modif-tree)))
