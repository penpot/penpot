;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.groups
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.util.names :as un]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn shapes-for-grouping
  [objects selected]
  (->> selected
       (map #(get objects %))
       (map #(assoc % ::index (cph/get-position-on-parent objects (:id %))))
       (sort-by ::index)))

(defn- get-empty-groups-after-group-creation
  "An auxiliar function that finds and returns a set of ids that
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
                    (-> (un/retrieve-used-names objects)
                        (un/generate-unique-name base-name)))

        selrect   (gsh/selection-rect shapes)
        group     (-> (cp/make-minimal-group frame-id selrect gname)
                      (cp/setup-shape selrect)
                      (assoc :shapes (mapv :id shapes)
                             :parent-id parent-id
                             :frame-id frame-id
                             :index (::index (first shapes))))

        ;; Look at the `get-empty-groups-after-group-creation`
        ;; docstring to understand the real purpose of this code
        ids-to-delete (get-empty-groups-after-group-creation objects parent-id shapes)

        changes   (-> (pcb/empty-changes it page-id)
                      (pcb/with-objects objects)
                      (pcb/add-object group {:index (::index (first shapes))})
                      (pcb/change-parent (:id group) shapes)
                      (pcb/remove-objects ids-to-delete))]

    [group changes]))

(defn prepare-remove-group
  [it page-id group objects]
  (let [children  (mapv #(get objects %) (:shapes group))
        parent-id (cph/get-parent-id objects (:id group))
        parent    (get objects parent-id)

        index-in-parent
        (->> (:shapes parent)
             (map-indexed vector)
             (filter #(#{(:id group)} (second %)))
             (ffirst))

        ids-to-detach (when (:component-id group)
                        (cph/get-children-ids objects (:id group)))

        detach-fn (fn [attrs]
                    (dissoc attrs
                            :component-id
                            :component-file
                            :component-root?
                            :remote-synced?
                            :shape-ref
                            :touched))]

    (cond-> (-> (pcb/empty-changes it page-id)
                (pcb/with-objects objects)
                (pcb/change-parent parent-id children index-in-parent)
                (pcb/remove-objects [(:id group)]))

      (some? ids-to-detach)
      (pcb/update-shapes ids-to-detach detach-fn))))

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
                (prepare-create-group it objects page-id shapes "Group-1" false)]
            (rx/of (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set (:id group))))))))))

(def ungroup-selected
  (ptk/reify ::ungroup-selected
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)
            is-group? #(or (= :bool (:type %)) (= :group (:type %)))
            lookup    #(get objects %)
            prepare   #(prepare-remove-group it page-id % objects)

            changes-list (sequence
                           (comp (map lookup)
                                 (filter is-group?)
                                 (map prepare))
                           (wsh/lookup-selected state))

            changes {:redo-changes (vec (mapcat :redo-changes changes-list))
                     :undo-changes (vec (mapcat :undo-changes changes-list))
                     :origin it}]

        (rx/of (dch/commit-changes changes))))))

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
                  (prepare-create-group it objects page-id shapes "Group-1" true))

                changes  (-> changes
                             (pcb/update-shapes (:shapes group)
                                                (fn [shape]
                                                  (assoc shape
                                                         :constraints-h :scale
                                                         :constraints-v :scale)))
                             (pcb/update-shapes [(:id group)]
                                                (fn [group]
                                                  (assoc group
                                                         :masked-group? true
                                                         :selrect (:selrect first-shape)
                                                         :points (:points first-shape)
                                                         :transform (:transform first-shape)
                                                         :transform-inverse (:transform-inverse first-shape))))
                             (pcb/resize-parents [(:id group)]))]

            (rx/of (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set (:id group))))))))))

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
                                                       (dissoc shape :masked-group?)))
                                  (pcb/resize-parents [(:id mask)])))
                            (-> (pcb/empty-changes it page-id)
                                (pcb/with-objects objects))
                            masked-groups)]

        (rx/of (dch/commit-changes changes))))))
