;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.changes-builder)

;; Auxiliary functions to help create a set of changes (undo + redo)

(defn empty-changes [origin page-id]
  (with-meta
    {:redo-changes []
     :undo-changes []
     :origin origin}
    {::page-id page-id}))

(defn add-obj
  [changes obj]
  (let [add-change
        {:type      :add-obj
         :id        (:id obj)
         :page-id   (::page-id (meta changes))
         :parent-id (:parent-id obj)
         :frame-id  (:frame-id obj)
         :index     (::index obj)
         :obj       (dissoc obj ::index :parent-id)}

        del-change
        {:type :del-obj
         :id (:id obj)
         :page-id (::page-id (meta changes))}]

    (-> changes
        (update :redo-changes conj add-change)
        (update :undo-changes #(into [del-change] %)))))

(defn change-parent
  [changes parent-id shapes]
  (let [set-parent-change
        {:type :mov-objects
         :parent-id parent-id
         :page-id (::page-id (meta changes))
         :shapes (->> shapes (mapv :id))}

        mk-undo-change
        (fn [shape]
          {:type :mov-objects
           :page-id (::page-id (meta changes))
           :parent-id (:parent-id shape)
           :shapes [(:id shape)]
           :index (::index shape)})

        undo-moves
        (->> shapes (mapv mk-undo-change))]

    (-> changes
        (update :redo-changes conj set-parent-change)
        (update :undo-changes #(into undo-moves %)))))
