;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.groups
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.logic.groups :as clg]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GROUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-shapes
  [id ids & {:keys [change-selection?] :or {change-selection? false}}]
  (ptk/reify ::group-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [id      (d/nilv id (uuid/next))
            page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            shapes  (clg/shapes-for-group-or-mask objects ids)
            parents (into #{} (map :parent-id) shapes)]
        (when-not (empty? shapes)
          (let [[group changes]
                (clg/prepare-create-group (pcb/empty-changes it) id objects page-id shapes "Group" false)]
            (rx/of (dch/commit-changes changes)
                   (when change-selection?
                     (dws/select-shapes (d/ordered-set (:id group))))
                   (ptk/data-event :layout/update {:ids parents}))))))))

(defn group-selected
  []
  (ptk/reify ::group-selected
    ptk/WatchEvent
    (watch [_ state _]
      ;; Raw ids: lookup-selected already clean-loops, which would drop
      ;; nested children of a selected variant main before we can.
      (let [selected (dsh/get-selected-ids state)]
        (rx/of (group-shapes nil selected :change-selection? true))))))

(defn ungroup-shapes
  [ids & {:keys [change-selection?] :or {change-selection? false}}]
  (ptk/reify ::ungroup-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)

            ids (clg/ungroup-ids objects ids)

            parents (into #{}
                          (comp (map #(cfh/get-parent objects %))
                                (keep :id))
                          ids)

            child-ids (clg/ungrouped-children objects ids)

            changes (clg/generate-ungroup-changes it page-id objects ids
                                                  (:pages-index (dsh/lookup-file-data state)))
            undo-id (js/Symbol)]

        (when-not (empty? ids)
          (rx/of (dwu/start-undo-transaction undo-id)
                 (dch/commit-changes changes)
                 (ptk/data-event :layout/update {:ids parents})
                 (dwu/commit-undo-transaction undo-id)
                 (when change-selection?
                   (dws/select-shapes child-ids))))))))

(defn ungroup-selected
  []
  (ptk/reify ::ungroup-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)]
        (rx/of (ungroup-shapes selected :change-selection? true))))))

(defn mask-group
  ([]
   (mask-group nil))
  ([ids]
   (ptk/reify ::mask-group
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id     (:current-page-id state)
             objects     (dsh/lookup-page-objects state page-id)
             shapes      (clg/shapes-for-group-or-mask
                          objects
                          (or ids (dsh/get-selected-ids state)))]
         (when-not (empty? shapes)
           (let [[group changes] (clg/generate-mask-changes it (uuid/next) page-id objects shapes)
                 undo-id (js/Symbol)]

             (rx/of (dwu/start-undo-transaction undo-id)
                    (dch/commit-changes changes)
                    (dws/select-shapes (d/ordered-set (:id group)))
                    (ptk/data-event :layout/update {:ids [(:id group)]})
                    (dwu/commit-undo-transaction undo-id)))))))))

(defn unmask-group
  ([]
   (unmask-group nil))

  ([ids]
   (ptk/reify ::unmask-group
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id  (:current-page-id state)
             objects  (dsh/lookup-page-objects state page-id)
             ids      (d/nilv ids (dsh/lookup-selected state))
             ;; an unmasked group takes the bounds of all its children, which
             ;; the layout it is in places again
             parents  (into #{} (keep #(:parent-id (get objects %))) ids)
             changes  (clg/generate-unmask-changes it page-id objects ids)]

         (rx/of (dch/commit-changes changes)
                (ptk/data-event :layout/update {:ids parents})))))))
