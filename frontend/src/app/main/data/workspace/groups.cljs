;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.groups
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn shapes-for-grouping
  [objects selected]
  (->> selected
       (cph/order-by-indexed-shapes objects)
       reverse
       (map #(get objects %))))

(defn- get-empty-groups-after-group-creation
  "An auxiliary function that finds and returns a set of ids that
  corresponds to groups that should be deleted after a group creation.

  The corner case happens when you selects two (or more) shapes that
  belongs each one to different groups, and after creating the new
  group, one (or many) groups can become empty because they have had a
  single shape which is moved to the created group."
  [objects parent-id shapes]
  (let [ids     (cph/clean-loops objects (into #{} (map :id) shapes))
        parents (into #{} (map #(cph/get-parent-id objects %)) ids)]
    (loop [current-id (first parents)
           to-check (rest parents)
           removed-id? ids
           result #{}]

      (if-not current-id
        ;; Base case, no next element
        result

        (let [group (get objects current-id)]
          (if (and (not= :frame (:type group))
                   (not= current-id parent-id)
                   (empty? (remove removed-id? (:shapes group))))

            ;; Adds group to the remove and check its parent
            (let [to-check (concat to-check [(cph/get-parent-id objects current-id)]) ]
              (recur (first to-check)
                     (rest to-check)
                     (conj removed-id? current-id)
                     (conj result current-id)))

            ;; otherwise recur
            (recur (first to-check)
                   (rest to-check)
                   removed-id?
                   result)))))))

(defn prepare-create-group
  [it objects page-id shapes base-name keep-name?]
  (let [frame-id  (:frame-id (first shapes))
        parent-id (:parent-id (first shapes))
        gname     (if (and keep-name?
                           (= (count shapes) 1)
                           (= (:type (first shapes)) :group))
                    (:name (first shapes))
                    base-name)

        selrect   (gsh/shapes->rect shapes)
        group-idx (->> shapes
                       last
                       :id
                       (cph/get-position-on-parent objects)
                       inc)

        group     (cts/setup-shape {:type :group
                                    :name gname
                                    :shapes (mapv :id shapes)
                                    :selrect selrect
                                    :parent-id parent-id
                                    :frame-id frame-id
                                    :index group-idx})

        ;; Shapes that are in a component, but are not root, must be detached,
        ;; because they will be now children of a non instance group.
        shapes-to-detach (filter ctk/in-component-copy-not-root? shapes)

        ;; Look at the `get-empty-groups-after-group-creation`
        ;; docstring to understand the real purpose of this code
        ids-to-delete (get-empty-groups-after-group-creation objects parent-id shapes)

        changes   (-> (pcb/empty-changes it page-id)
                      (pcb/with-objects objects)
                      (pcb/add-object group {:index group-idx})
                      (pcb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
                      (pcb/change-parent (:id group) (reverse shapes))
                      (pcb/update-shapes (map :id shapes-to-detach) ctk/detach-shape)
                      (pcb/remove-objects ids-to-delete))]

    [group changes]))

(defn remove-group-changes
  [it page-id group objects]
  (let [children (->> (:shapes group)
                      (cph/order-by-indexed-shapes objects)
                      (mapv #(get objects %)))
        parent-id (cph/get-parent-id objects (:id group))
        parent    (get objects parent-id)

        index-in-parent
        (->> (:shapes parent)
             (map-indexed vector)
             (filter #(#{(:id group)} (second %)))
             (ffirst)
             inc)

        ;; Shapes that are in a component (including root) must be detached,
        ;; because cannot be easyly synchronized back to the main component.
        shapes-to-detach (filter ctk/in-component-copy?
                                 (cph/get-children-with-self objects (:id group)))]

    (-> (pcb/empty-changes it page-id)
        (pcb/with-objects objects)
        (pcb/change-parent parent-id children index-in-parent)
        (pcb/remove-objects [(:id group)])
        (pcb/update-shapes (map :id shapes-to-detach) ctk/detach-shape))))

(defn remove-frame-changes
  [it page-id frame objects]
  (let [children (->> (:shapes frame)
                      (cph/order-by-indexed-shapes objects)
                      (mapv #(get objects %)))
        parent-id     (cph/get-parent-id objects (:id frame))
        idx-in-parent (->> (:id frame)
                           (cph/get-position-on-parent objects)
                           inc)]

    (-> (pcb/empty-changes it page-id)
        (pcb/with-objects objects)
        (cond-> (ctl/any-layout? frame)
          (pcb/update-shapes (:shapes frame) ctl/remove-layout-item-data))
        (pcb/change-parent parent-id children idx-in-parent)
        (pcb/remove-objects [(:id frame)]))))


(defn- clone-component-shapes-changes
  [changes shape objects]
  (let [shape-parent-id (:parent-id shape)
        new-shape-id (uuid/next)
        [_ new-shapes _]
        (ctst/clone-object shape
                      shape-parent-id
                      objects
                      (fn [object _]
                        (cond-> object
                          (= new-shape-id (:parent-id object))
                          (assoc :parent-id shape-parent-id)))
                      (fn [object _] object)
                      new-shape-id
                      false)

        new-shapes (->> new-shapes
                        (filter #(not= (:id %) new-shape-id)))]
    (reduce
     (fn [changes shape]
       (pcb/add-object changes shape))
     changes
     new-shapes)))

(defn remove-component-changes
  [it page-id shape objects file-data file]
  (let [page (ctpl/get-page file-data page-id)
        components-v2 (dm/get-in file-data [:options :components-v2])
        ;; In order to ungroup a component, we first make a clone of its shapes,
        ;; and then we delete it
        changes (-> (pcb/empty-changes it page-id)
                    (pcb/with-objects objects)
                    (pcb/with-library-data file-data)
                    (pcb/with-page page)
                    (clone-component-shapes-changes shape objects)
                    (dwsh/delete-shapes-changes file page objects [(:id shape)] it components-v2))]
    ;; TODO: Should we call detach-comment-thread ?
    changes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GROUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def group-selected
  (ptk/reify ::group-selected
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)
            selected (cph/clean-loops objects selected)
            shapes   (shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [[group changes]
                (prepare-create-group it objects page-id shapes "Group" false)]
            (rx/of (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set (:id group))))))))))

(def ungroup-selected
  (ptk/reify ::ungroup-selected
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)
            file-data (get state :workspace-data)
            file      (wsh/get-local-file state)

            prepare
            (fn [shape-id]
              (let [shape (get objects shape-id)]
                (cond
                  (ctk/main-instance? shape)
                  (remove-component-changes it page-id shape objects file-data file)

                  (or (cph/group-shape? shape) (cph/bool-shape? shape))
                  (remove-group-changes it page-id shape objects)

                  (cph/frame-shape? shape)
                  (remove-frame-changes it page-id shape objects))))

            selected (wsh/lookup-selected state)
            changes-list (sequence
                          (keep prepare)
                          selected)

            parents (into #{}
                          (comp (map #(cph/get-parent objects %))
                                (keep :id))
                          selected)

            child-ids
            (into (d/ordered-set)
                  (mapcat #(dm/get-in objects [% :shapes]))
                  selected)

            changes {:redo-changes (vec (mapcat :redo-changes changes-list))
                     :undo-changes (vec (mapcat :undo-changes changes-list))
                     :origin it}
            undo-id (js/Symbol)]

        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (ptk/data-event :layout/update parents)
               (dwu/commit-undo-transaction undo-id)
               (dws/select-shapes child-ids))))))

(def mask-group
  (ptk/reify ::mask-group
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id     (:current-page-id state)
            objects     (wsh/lookup-page-objects state page-id)
            selected    (wsh/lookup-selected state)
            selected    (cph/clean-loops objects selected)
            shapes      (shapes-for-grouping objects selected)
            first-shape (first shapes)]
        (when-not (empty? shapes)
          (let [;; If the selected shape is a group, we can use it. If not,
                ;; create a new group and set it as masked.
                [group changes]
                (if (and (= (count shapes) 1)
                         (= (:type (first shapes)) :group))
                  [first-shape (-> (pcb/empty-changes it page-id)
                                   (pcb/with-objects objects))]
                  (prepare-create-group it objects page-id shapes "Mask" true))

                changes  (-> changes
                             (pcb/update-shapes (:shapes group)
                                                (fn [shape]
                                                  (assoc shape
                                                         :constraints-h :scale
                                                         :constraints-v :scale)))
                             (pcb/update-shapes [(:id group)]
                                                (fn [group]
                                                  (assoc group
                                                         :masked-group true
                                                         :selrect (:selrect first-shape)
                                                         :points (:points first-shape)
                                                         :transform (:transform first-shape)
                                                         :transform-inverse (:transform-inverse first-shape))))
                             (pcb/resize-parents [(:id group)]))
                undo-id (js/Symbol)]

            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set (:id group)))
                   (ptk/data-event :layout/update [(:id group)])
                   (dwu/commit-undo-transaction undo-id))))))))

(def unmask-group
  (ptk/reify ::unmask-group
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)

            masked-groups (->> (wsh/lookup-selected state)
                               (map  #(get objects %))
                               (filter #(or (= :bool (:type %)) (= :group (:type %)))))

            changes (reduce (fn [changes mask]
                              (-> changes
                                  (pcb/update-shapes [(:id mask)]
                                                     (fn [shape]
                                                       (dissoc shape :masked-group)))
                                  (pcb/resize-parents [(:id mask)])))
                            (-> (pcb/empty-changes it page-id)
                                (pcb/with-objects objects))
                            masked-groups)]

        (rx/of (dch/commit-changes changes))))))
