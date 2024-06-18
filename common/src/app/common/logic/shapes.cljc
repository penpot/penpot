;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.logic.shapes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.page :as ctp]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

(defn generate-update-shapes
  [changes ids update-fn objects {:keys [attrs ignore-tree ignore-touched with-objects?]}]
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
        changes (pcb/update-shapes changes grid-ids ctl/assign-cell-positions {:with-objects? true})
        changes (pcb/reorder-grid-children changes ids)]
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

        ids-set (set ids-to-delete)
        guides-to-remove
        (->> (dm/get-in page [:options :guides])
             (vals)
             (filter #(contains? ids-set (:frame-id %)))
             (map :id))

        guides
        (->> guides-to-remove
             (reduce dissoc (dm/get-in page [:options :guides])))

        starting-flows
        (filter (fn [flow]
                  ;; If any of the deleted is a frame that starts a flow,
                  ;; this must be deleted, too.
                  (contains? ids-to-delete (:starting-frame flow)))
                (-> page :options :flows))

        all-parents
        (reduce (fn [res id]
                  ;; All parents of any deleted shape must be resized.
                  (into res (cfh/get-parent-ids objects id)))
                (d/ordered-set)
                ids-to-delete)

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
        (into (d/ordered-set) (find-all-empty-parents #{}))

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

        changes (-> changes
                    (pcb/set-page-option :guides guides))

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
                                                                interactions)))))
                    (cond-> (seq starting-flows)
                      (pcb/update-page-option :flows (fn [flows]
                                                       (->> (map :id starting-flows)
                                                            (reduce ctp/remove-flow flows))))))]
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
        cell (or cell (ctl/get-cell-by-index parent to-index))]

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
                 (-> frame
                     ;; Assign the cell when pushing into a specific grid cell
                     (cond-> (some? cell)
                       (-> (ctl/free-cell-shapes ids)
                           (ctl/push-into-cell ids (:row cell) (:column cell))
                           (ctl/assign-cells objects)))
                     (ctl/assign-cell-positions objects)))
               {:with-objects? true})
              (pcb/reorder-grid-children [parent-id])))

        ;; If parent locked, lock the added shapes
        (cond-> (:blocked parent)
          (pcb/update-shapes ids #(assoc % :blocked true)))

        ;; Resize parent containers that need to
        (pcb/resize-parents parents))))




(defn change-show-in-viewer [shape hide?]
  (cond-> (assoc shape :hide-in-viewer hide?)
    ;; When a frame is no longer shown in view mode, it cannot have interactions
    hide?
    (dissoc :interactions)))

(defn add-new-interaction [shape interaction]
  (-> shape
      (update :interactions ctsi/add-interaction interaction)
      ;; When a interaction is created, the frame must be shown in view mode
      (dissoc :hide-in-viewer)))
