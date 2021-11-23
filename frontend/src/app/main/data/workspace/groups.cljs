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
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn shapes-for-grouping
  [objects selected]
  (->> selected
       (map #(get objects %))
       (filter #(not= :frame (:type %)))
       (map #(assoc % ::index (cp/position-on-parent (:id %) objects)))
       (sort-by ::index)))

(defn- get-empty-groups-after-group-creation
  "An auxiliar function that finds and returns a set of ids that
  corresponds to groups that should be deleted after a group creation.

  The corner case happens when you selects two (or more) shapes that
  belongs each one to different groups, and after creating the new
  group, one (or many) groups can become empty because they have had a
  single shape which is moved to the created group."
  [objects parent-id shapes]
  (let [ids (cp/clean-loops objects (into #{} (map :id) shapes))
        parents (->> ids
                     (reduce #(conj %1 (cp/get-parent %2 objects))
                             #{}))]
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
            (let [to-check (d/concat [] to-check [(cp/get-parent current-id objects)]) ]
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
  [objects page-id shapes base-name keep-name?]
  (let [frame-id  (:frame-id (first shapes))
        parent-id (:parent-id (first shapes))
        gname     (if (and keep-name?
                           (= (count shapes) 1)
                           (= (:type (first shapes)) :group))
                    (:name (first shapes))
                    (-> (dwc/retrieve-used-names objects)
                        (dwc/generate-unique-name base-name)))

        selrect   (gsh/selection-rect shapes)
        group     (-> (cp/make-minimal-group frame-id selrect gname)
                      (gsh/setup selrect)
                      (assoc :shapes (mapv :id shapes)))

        rchanges  [{:type :add-obj
                    :id (:id group)
                    :page-id page-id
                    :frame-id frame-id
                    :parent-id parent-id
                    :obj group
                    :index (::index (first shapes))}

                   {:type :mov-objects
                    :page-id page-id
                    :parent-id (:id group)
                    :shapes (mapv :id shapes)}]

        uchanges  (-> (mapv (fn [obj]
                              {:type :mov-objects
                               :page-id page-id
                               :parent-id (:parent-id obj)
                               :index (::index obj)
                               :shapes [(:id obj)]})
                            shapes)
                      (conj {:type :del-obj
                             :id (:id group)
                             :page-id page-id}))

        ;; Look at the `get-empty-groups-after-group-creation`
        ;; docstring to understand the real purpose of this code
        ids-to-delete (get-empty-groups-after-group-creation objects parent-id shapes)

        delete-group
        (fn [changes id]
          (conj changes {:type :del-obj
                         :id id
                         :page-id page-id}))

        add-deleted-group
        (fn [changes id]
          (let [obj (-> (get objects id)
                        (dissoc :shapes))]
            (into [{:type :add-obj
                    :id id
                    :page-id page-id
                    :frame-id (:frame-id obj)
                    :parent-id (:parent-id obj)
                    :obj obj
                    :index (::index obj)}]
                  changes)))

        rchanges (->> ids-to-delete
                      (reduce delete-group rchanges))

        uchanges (->> ids-to-delete
                      (reduce add-deleted-group uchanges))]
    [group rchanges uchanges]))

(defn prepare-remove-group
  [page-id group objects]
  (let [shapes    (:shapes group)
        parent-id (cp/get-parent (:id group) objects)
        parent    (get objects parent-id)
        index-in-parent (->> (:shapes parent)
                             (map-indexed vector)
                             (filter #(#{(:id group)} (second %)))
                             (ffirst))
        rchanges [{:type :mov-objects
                   :page-id page-id
                   :parent-id parent-id
                   :shapes shapes
                   :index index-in-parent}
                  {:type :del-obj
                   :page-id page-id
                   :id (:id group)}]
        uchanges [{:type :add-obj
                   :page-id page-id
                   :id (:id group)
                   :frame-id (:frame-id group)
                   :obj (assoc group :shapes [])}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id (:id group)
                   :shapes shapes}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id parent-id
                   :shapes [(:id group)]
                   :index index-in-parent}]]
    [rchanges uchanges]))


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
            selected (cp/clean-loops objects selected)
            shapes   (shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [[group rchanges uchanges]
                (prepare-create-group objects page-id shapes "Group-1" false)]
            (rx/of (dch/commit-changes {:redo-changes rchanges
                                        :undo-changes uchanges
                                        :origin it})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))

(def ungroup-selected
  (ptk/reify ::ungroup-selected
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)
            group-id (first selected)
            group    (get objects group-id)]
        (when (and (= 1 (count selected))
                   (contains? #{:group :bool} (:type group)))
          (let [[rchanges uchanges]
                (prepare-remove-group page-id group objects)]
            (rx/of (dch/commit-changes {:redo-changes rchanges
                                        :undo-changes uchanges
                                        :origin it}))))))))

(def mask-group
  (ptk/reify ::mask-group
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)
            selected (cp/clean-loops objects selected)
            shapes   (shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [;; If the selected shape is a group, we can use it. If not,
                ;; create a new group and set it as masked.
                [group rchanges uchanges]
                (if (and (= (count shapes) 1)
                         (= (:type (first shapes)) :group))
                  [(first shapes) [] []]
                  (prepare-create-group objects page-id shapes "Group-1" true))

                rchanges (d/concat rchanges
                                   [{:type :mod-obj
                                     :page-id page-id
                                     :id (:id group)
                                     :operations [{:type :set
                                                   :attr :masked-group?
                                                   :val true}
                                                  {:type :set
                                                   :attr :selrect
                                                   :val (-> shapes first :selrect)}
                                                  {:type :set
                                                   :attr :points
                                                   :val (-> shapes first :points)}
                                                  {:type :set
                                                   :attr :transform
                                                   :val (-> shapes first :transform)}
                                                  {:type :set
                                                   :attr :transform-inverse
                                                   :val (-> shapes first :transform-inverse)}]}
                                    {:type :reg-objects
                                     :page-id page-id
                                     :shapes [(:id group)]}])

                uchanges (conj uchanges
                               {:type :mod-obj
                                :page-id page-id
                                :id (:id group)
                                :operations [{:type :set
                                              :attr :masked-group?
                                              :val nil}]}
                               {:type :reg-objects
                                :page-id page-id
                                :shapes [(:id group)]})]

            (rx/of (dch/commit-changes {:redo-changes rchanges
                                        :undo-changes uchanges
                                        :origin it})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))

(def unmask-group
  (ptk/reify ::unmask-group
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)]
        (when (= (count selected) 1)
          (let [group (get objects (first selected))

                rchanges [{:type :mod-obj
                           :page-id page-id
                           :id (:id group)
                           :operations [{:type :set
                                         :attr :masked-group?
                                         :val nil}]}
                          {:type :reg-objects
                           :page-id page-id
                           :shapes [(:id group)]}]

                uchanges [{:type :mod-obj
                           :page-id page-id
                           :id (:id group)
                           :operations [{:type :set
                                         :attr :masked-group?
                                         :val (:masked-group? group)}]}
                          {:type :reg-objects
                           :page-id page-id
                           :shapes [(:id group)]}]]

            (rx/of (dch/commit-changes {:redo-changes rchanges
                                        :undo-changes uchanges
                                        :origin it})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))


