;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.logic.shapes
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.token :as cto]
   [app.common.uuid :as uuid]))

(defn- generate-unapply-tokens
  "When updating attributes that have a token applied, we must unapply it, because the value
   of the attribute now has been given directly, and does not come from the token."
  [changes objects changed-sub-attr]
  (let [mod-obj-changes (->> (:redo-changes changes)
                             (filter #(= (:type %) :mod-obj)))

        check-attr (fn [shape changes attr]
                     (let [tokens      (get shape :applied-tokens {})
                           token-attrs (cto/shape-attr->token-attrs attr changed-sub-attr)]
                       (if (some #(contains? tokens %) token-attrs)
                         (pcb/update-shapes changes [(:id shape)] #(cto/unapply-token-id % token-attrs))
                         changes)))

        check-shape (fn [changes mod-obj-change]
                      (let [shape (get objects (:id mod-obj-change))
                            xf (comp (filter #(= (:type %) :set))
                                     (map :attr))
                            attrs (into [] xf (:operations mod-obj-change))]
                        (reduce (partial check-attr shape)
                                changes
                                attrs)))]
    (reduce check-shape
            changes
            mod-obj-changes)))

(defn generate-update-shapes
  [changes ids update-fn objects {:keys [attrs changed-sub-attr ignore-tree ignore-touched with-objects?]}]
  (let [changes   (reduce
                   (fn [changes id]
                     (let [opts {:attrs attrs
                                 :ignore-geometry? (get ignore-tree id)
                                 :ignore-touched ignore-touched
                                 :with-objects? with-objects?}]
                       (pcb/update-shapes changes [id] update-fn (d/without-nils opts))))
                   (-> changes
                       (pcb/with-objects objects))
                   ids)
        grid-ids (->> ids (filter (partial ctl/grid-layout? objects)))
        changes (-> changes
                    (pcb/update-shapes grid-ids ctl/assign-cell-positions {:with-objects? true})
                    (pcb/reorder-grid-children ids)
                    (cond->
                     (not ignore-touched)
                      (generate-unapply-tokens objects changed-sub-attr)))]
    changes))

(defn- generate-update-shape-flags
  [changes ids objects {:keys [blocked hidden] :as flags}]
  (let [update-fn
        (fn [obj]
          (cond-> obj
            (boolean? blocked) (assoc :blocked blocked)
            (boolean? hidden) (assoc :hidden hidden)))

        ids     (if (boolean? blocked)
                  (into ids (->> ids (mapcat #(cfh/get-children-ids objects %))))
                  ids)]
    (-> changes
        (pcb/update-shapes ids update-fn {:attrs #{:blocked :hidden}}))))

(defn generate-delete-shapes
  [changes file page objects ids {:keys [components-v2 ignore-touched component-swap]}]
  (let [ids           (cfh/clean-loops objects ids)

        in-component-copy?
        (fn [shape-id]
          ;; Look for shapes that are inside a component copy, but are
          ;; not the root. In this case, they must not be deleted,
          ;; but hidden (to be able to recover them more easily).
          ;; Unless we are doing a component swap, in which case we want
          ;; to delete the old shape
          (let [shape           (get objects shape-id)]
            (and (ctn/has-any-copy-parent? objects shape)
                 (not component-swap))))

        [ids-to-delete ids-to-hide]
        (if components-v2
          (loop [ids-seq       (seq ids)
                 ids-to-delete []
                 ids-to-hide   []]
            (let [id (first ids-seq)]
              (if (nil? id)
                [ids-to-delete ids-to-hide]
                (if (in-component-copy? id)
                  (recur (rest ids-seq)
                         ids-to-delete
                         (conj ids-to-hide id))
                  (recur (rest ids-seq)
                         (conj ids-to-delete id)
                         ids-to-hide)))))
          [ids []])

        changes (-> changes
                    (pcb/with-page page)
                    (pcb/with-objects objects)
                    (pcb/with-library-data file))

        lookup  (d/getf objects)

        groups-to-unmask
        (reduce (fn [group-ids id]
                  ;; When the shape to delete is the mask of a masked group,
                  ;; the mask condition must be removed, and it must be
                  ;; converted to a normal group.
                  (let [obj    (lookup id)
                        parent (lookup (:parent-id obj))]
                    (if (and (:masked-group parent)
                             (= id (first (:shapes parent))))
                      (conj group-ids (:id parent))
                      group-ids)))
                #{}
                ids-to-delete)

        interacting-shapes
        (filter (fn [shape]
                  ;; If any of the deleted shapes is the destination of
                  ;; some interaction, this must be deleted, too.
                  (let [interactions (:interactions shape)]
                    (some #(and (ctsi/has-destination %)
                                (contains? ids-to-delete (:destination %)))
                          interactions)))
                (vals objects))

        changes
        (reduce (fn [changes {:keys [id] :as flow}]
                  (if (contains? ids-to-delete (:starting-frame flow))
                    (pcb/set-flow changes id nil)
                    changes))
                changes
                (:flows page))


        all-parents
        (reduce (fn [res id]
                  ;; All parents of any deleted shape must be resized.
                  (into res (cfh/get-parent-ids objects id)))
                (d/ordered-set)
                (concat ids-to-delete ids-to-hide))

        all-children
        (->> ids-to-delete ;; Children of deleted shapes must be also deleted.
             (reduce (fn [res id]
                       (into res (cfh/get-children-ids objects id)))
                     [])
             (reverse)
             (into (d/ordered-set)))

        find-all-empty-parents
        (fn recursive-find-empty-parents [empty-parents]
          (let [all-ids   (into empty-parents ids-to-delete)
                contains? (partial contains? all-ids)
                xform     (comp (map lookup)
                                (filter #(or (cfh/group-shape? %) (cfh/bool-shape? %)))
                                (remove #(->> (:shapes %) (remove contains?) seq))
                                (map :id))
                parents   (into #{} xform all-parents)]
            (if (= empty-parents parents)
              empty-parents
              (recursive-find-empty-parents parents))))

        empty-parents
        ;; Any parent whose children are all deleted, must be deleted too.
        ;; Unless we are during a component swap: in this case we are replacing a shape by
        ;; other one, so must not delete empty parents.
        (if-not component-swap
          (into (d/ordered-set) (find-all-empty-parents #{}))
          #{})

        components-to-delete
        (if components-v2
          (reduce (fn [components id]
                    (let [shape (get objects id)]
                      (if (and (= (:component-file shape) (:id file)) ;; Main instances should exist only in local file
                               (:main-instance shape))                ;; but check anyway
                        (conj components (:component-id shape))
                        components)))
                  []
                  (into ids-to-delete all-children))
          [])

        ids-set (set ids-to-delete)

        guides-to-delete
        (->> (:guides page)
             (vals)
             (filter #(contains? ids-set (:frame-id %)))
             (map :id))

        changes (reduce (fn [changes guide-id]
                          (pcb/set-flow changes guide-id nil))
                        changes
                        guides-to-delete)

        changes (reduce (fn [changes component-id]
                          ;; It's important to delete the component before the main instance, because we
                          ;; need to store the instance position if we want to restore it later.
                          (pcb/delete-component changes component-id (:id page)))
                        changes
                        components-to-delete)

        changes (-> changes
                    (generate-update-shape-flags ids-to-hide objects {:hidden true})
                    (pcb/remove-objects all-children {:ignore-touched true})
                    (pcb/remove-objects ids-to-delete {:ignore-touched ignore-touched})
                    (pcb/remove-objects empty-parents)
                    (pcb/resize-parents all-parents)
                    (pcb/update-shapes groups-to-unmask
                                       (fn [shape]
                                         (assoc shape :masked-group false)))
                    (pcb/update-shapes (map :id interacting-shapes)
                                       (fn [shape]
                                         (d/update-when shape :interactions
                                                        (fn [interactions]
                                                          (into []
                                                                (remove #(and (ctsi/has-destination %)
                                                                              (contains? ids-to-delete (:destination %))))
                                                                interactions))))))]
    [all-parents changes]))


(defn generate-relocate
  [changes objects parent-id page-id to-index ids & {:keys [cell ignore-parents?]}]
  (let [ids    (cfh/order-by-indexed-shapes objects ids)
        shapes (map (d/getf objects) ids)
        parent (get objects parent-id)
        all-parents (into #{parent-id} (map #(cfh/get-parent-id objects %)) ids)
        parents  (if ignore-parents? #{parent-id} all-parents)

        children-ids
        (->> ids
             (mapcat #(cfh/get-children-ids-with-self objects %)))

        child-heads
        (->> ids
             (mapcat #(ctn/get-child-heads objects %))
             (map :id))

        component-main-parent
        (ctn/find-component-main objects parent false)

        groups-to-delete
        (loop [current-id  (first parents)
               to-check    (rest parents)
               removed-id? (set ids)
               result #{}]

          (if-not current-id
                   ;; Base case, no next element
            result

            (let [group (get objects current-id)]
              (if (and (not= :frame (:type group))
                       (not= current-id parent-id)
                       (empty? (remove removed-id? (:shapes group))))

                       ;; Adds group to the remove and check its parent
                (let [to-check (concat to-check [(cfh/get-parent-id objects current-id)])]
                  (recur (first to-check)
                         (rest to-check)
                         (conj removed-id? current-id)
                         (conj result current-id)))

                       ;; otherwise recur
                (recur (first to-check)
                       (rest to-check)
                       removed-id?
                       result)))))

        groups-to-unmask
        (reduce (fn [group-ids id]
                  ;; When a masked group loses its mask shape, because it's
                  ;; moved outside the group, the mask condition must be
                  ;; removed, and it must be converted to a normal group.
                  (let [obj (get objects id)
                        parent (get objects (:parent-id obj))]
                    (if (and (:masked-group parent)
                             (= id (first (:shapes parent)))
                             (not= (:id parent) parent-id))
                      (conj group-ids (:id parent))
                      group-ids)))
                #{}
                ids)

        ;; TODO: Probably implementing this using loop/recur will
        ;; be more efficient than using reduce and continuous data
        ;; desturcturing.

        ;; Sets the correct components metadata for the moved shapes
        ;; `shapes-to-detach` Detach from a component instance a shape that was inside a component and is moved outside
        ;; `shapes-to-deroot` Removes the root flag from a component instance moved inside another component
        ;; `shapes-to-reroot` Adds a root flag when a nested component instance is moved outside
        [shapes-to-detach shapes-to-deroot shapes-to-reroot]
        (reduce (fn [[shapes-to-detach shapes-to-deroot shapes-to-reroot] id]
                  (let [shape                  (get objects id)
                        parent                 (get objects parent-id)
                        component-shape        (ctn/get-component-shape objects shape)
                        component-shape-parent (ctn/get-component-shape objects parent {:allow-main? true})
                        root-parent            (ctn/get-instance-root objects parent)

                        detach? (and (ctk/in-component-copy-not-head? shape)
                                     (not= (:id component-shape)
                                           (:id component-shape-parent)))
                        deroot? (and (ctk/instance-root? shape)
                                     root-parent)
                        reroot? (and (ctk/subinstance-head? shape)
                                     (not component-shape-parent))

                        ids-to-detach (when detach?
                                        (cons id (cfh/get-children-ids objects id)))]

                    [(cond-> shapes-to-detach detach? (into ids-to-detach))
                     (cond-> shapes-to-deroot deroot? (conj id))
                     (cond-> shapes-to-reroot reroot? (conj id))]))
                [[] [] []]
                (->> ids
                     (mapcat #(ctn/get-child-heads objects %))
                     (map :id)))

        index-cell-data  (when to-index (ctl/get-cell-by-index parent to-index))
        cell (or cell (and index-cell-data [(:row index-cell-data) (:column index-cell-data)]))]

    (-> changes
        (pcb/with-page-id page-id)
        (pcb/with-objects objects)

        ;; Remove layout-item properties when moving a shape outside a layout
        (cond-> (not (ctl/any-layout? parent))
          (pcb/update-shapes ids ctl/remove-layout-item-data))

        ;; Remove the hide in viewer flag
        (cond-> (and (not= uuid/zero parent-id) (cfh/frame-shape? parent))
          (pcb/update-shapes ids #(cond-> % (cfh/frame-shape? %) (assoc :hide-in-viewer true))))

        ;; Remove the swap slots if it is moving to a different component
        (pcb/update-shapes
         child-heads
         (fn [shape]
           (cond-> shape
             (not= component-main-parent (ctn/find-component-main objects shape false))
             (ctk/remove-swap-slot))))

        ;; Remove component-root property when moving a shape inside a component
        (cond-> (ctn/get-instance-root objects parent)
          (pcb/update-shapes children-ids #(dissoc % :component-root)))

        ;; Add component-root property when moving a component outside a component
        (cond-> (not (ctn/get-instance-root objects parent))
          (pcb/update-shapes child-heads #(assoc % :component-root true)))

        ;; Move the shapes
        (pcb/change-parent parent-id
                           shapes
                           to-index)

        ;; Remove empty groups
        (pcb/remove-objects groups-to-delete)

        ;; Unmask groups whose mask have moved outside
        (pcb/update-shapes groups-to-unmask
                           (fn [shape]
                             (assoc shape :masked-group false)))

        ;; Detach shapes moved out of their component
        (pcb/update-shapes shapes-to-detach ctk/detach-shape)

        ;; Make non root a component moved inside another one
        (pcb/update-shapes shapes-to-deroot
                           (fn [shape]
                             (assoc shape :component-root nil)))

        ;; Make root a subcomponent moved outside its parent component
        (pcb/update-shapes shapes-to-reroot
                           (fn [shape]
                             (assoc shape :component-root true)))

        ;; Reset constraints depending on the new parent
        (pcb/update-shapes ids
                           (fn [shape]
                             (let [frame-id    (if (= (:type parent) :frame)
                                                 (:id parent)
                                                 (:frame-id parent))
                                   moved-shape (assoc shape
                                                      :parent-id parent-id
                                                      :frame-id frame-id)]
                               (assoc shape
                                      :constraints-h (gsh/default-constraints-h moved-shape)
                                      :constraints-v (gsh/default-constraints-v moved-shape))))
                           {:ignore-touched true})

        ;; Fix the sizing when moving a shape
        (pcb/update-shapes parents
                           (fn [parent]
                             (if (ctl/flex-layout? parent)
                               (cond-> parent
                                 (ctl/change-h-sizing? (:id parent) objects (:shapes parent))
                                 (assoc :layout-item-h-sizing :fix)

                                 (ctl/change-v-sizing? (:id parent) objects (:shapes parent))
                                 (assoc :layout-item-v-sizing :fix))
                               parent)))

        ;; Change the grid cell in a grid layout
        (cond-> (ctl/grid-layout? objects parent-id)
          (-> (pcb/update-shapes
               [parent-id]
               (fn [frame objects]
                 (let [[row column] cell]
                   (-> frame
                       ;; Assign the cell when pushing into a specific grid cell
                       (cond-> (some? cell)
                         (-> (ctl/free-cell-shapes ids)
                             (ctl/push-into-cell ids row column)
                             (ctl/assign-cells objects)))
                       (ctl/assign-cell-positions objects))))
               {:with-objects? true})
              (pcb/reorder-grid-children [parent-id])))

        ;; If parent locked, lock the added shapes
        (cond-> (:blocked parent)
          (pcb/update-shapes ids #(assoc % :blocked true)))

        ;; Resize parent containers that need to
        (pcb/resize-parents parents))))

(defn change-show-in-viewer
  [shape hide?]
  (assoc shape :hide-in-viewer hide?))

(defn add-new-interaction
  [shape interaction]
  (update shape :interactions ctsi/add-interaction interaction))

(defn show-in-viewer
  [shape]
  (dissoc shape :hide-in-viewer))
