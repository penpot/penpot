;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shapes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.schema :as sm]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.page :as ctp]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dc]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(def valid-shape-map?
  (sm/pred-fn ::cts/shape))

(defn prepare-add-shape
  [changes shape objects _selected]
  (let [;; WTF: index again? revisit please
        index   (:index (meta shape))

        changes (-> changes
                    (pcb/with-objects objects)
                    (cond-> (some? index)
                      (pcb/add-object shape {:index index}))
                    (cond-> (nil? index)
                      (pcb/add-object shape))
                    (cond-> (some? (:parent-id shape))
                      (pcb/change-parent (:parent-id shape) [shape]))
                    (cond-> (ctl/grid-layout? objects (:parent-id shape))
                      (pcb/update-shapes [(:parent-id shape)] ctl/assign-cells)))]

    [shape changes]))

(defn add-shape
  ([shape]
   (add-shape shape {}))
  ([shape {:keys [no-select? no-update-layout?]}]

   (dm/verify!
    "expected a valid shape"
    (cts/valid-shape? shape))

   (ptk/reify ::add-shape
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id  (:current-page-id state)
             objects  (wsh/lookup-page-objects state page-id)
             selected (wsh/lookup-selected state)

             [shape changes]
             (-> (pcb/empty-changes it page-id)
                 (prepare-add-shape shape objects selected))

             undo-id (js/Symbol)]

         (rx/concat
          (rx/of (dwu/start-undo-transaction undo-id)
                 (dch/commit-changes changes)
                 (when-not no-update-layout?
                   (ptk/data-event :layout/update [(:parent-id shape)]))
                 (when-not no-select?
                   (dws/select-shapes (d/ordered-set (:id shape))))
                 (dwu/commit-undo-transaction undo-id))
          (when (cph/text-shape? shape)
            (->> (rx/of (dwe/start-edition-mode (:id shape)))
                 (rx/observe-on :async)))))))))

(defn prepare-move-shapes-into-frame
  [changes frame-id shapes objects]
  (let [ordered-indexes (cph/order-by-indexed-shapes objects shapes)
        parent-id (get-in objects [frame-id :parent-id])
        ordered-indexes (->> ordered-indexes (remove #(= % parent-id)))
        to-move-shapes (map (d/getf objects) ordered-indexes)]
    (when (d/not-empty? to-move-shapes)
      (-> changes
          (cond-> (not (ctl/any-layout? objects frame-id))
            (pcb/update-shapes ordered-indexes ctl/remove-layout-item-data))
          (pcb/update-shapes ordered-indexes #(cond-> % (cph/frame-shape? %) (assoc :hide-in-viewer true)))
          (pcb/change-parent frame-id to-move-shapes 0)
          (cond-> (ctl/grid-layout? objects frame-id)
            (pcb/update-shapes [frame-id] ctl/assign-cells))))))

(defn move-shapes-into-frame [frame-id shapes]
  (ptk/reify ::move-shapes-into-frame
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects))
            changes (prepare-move-shapes-into-frame changes
                                                    frame-id
                                                    shapes
                                                    objects)]
        (if (some? changes)
          (rx/of (dch/commit-changes changes))
          (rx/empty))))))

(declare real-delete-shapes)
(declare update-shape-flags)

(defn delete-shapes
  ([ids] (delete-shapes nil ids))
  ([page-id ids]
   (dm/assert! (sm/set-of-uuid? ids))
   (ptk/reify ::delete-shapes
     ptk/WatchEvent
     (watch [it state _]
       (let [file-id       (:current-file-id state)
             page-id       (or page-id (:current-page-id state))
             file          (wsh/get-file state file-id)
             page          (wsh/lookup-page state page-id)
             objects       (wsh/lookup-page-objects state page-id)

             components-v2 (features/active-feature? state :components-v2)

             ids           (cph/clean-loops objects ids)

             in-component-copy?
             (fn [shape-id]
               ;; Look for shapes that are inside a component copy, but are
               ;; not the root. In this case, they must not be deleted,
               ;; but hidden (to be able to recover them more easily).
               (let [shape           (get objects shape-id)
                     component-shape (ctn/get-component-shape objects shape)]
                 (and (ctk/in-component-copy? shape)
                      (not= shape component-shape)
                      (not (ctk/main-instance? component-shape)))))

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

             undo-id (js/Symbol)]

         (rx/concat
          (rx/of (dwu/start-undo-transaction undo-id)
                 (update-shape-flags ids-to-hide {:hidden true}))
          (real-delete-shapes file page objects ids-to-delete it components-v2)
          (rx/of (dwu/commit-undo-transaction undo-id))))))))

(defn- real-delete-shapes
  [file page objects ids it components-v2]
  (let [lookup  (d/getf objects)

        groups-to-unmask
        (reduce (fn [group-ids id]
                  ;; When the shape to delete is the mask of a masked group,
                  ;; the mask condition must be removed, and it must be
                  ;; converted to a normal group.
                  (let [obj    (lookup id)
                        parent (lookup (:parent-id obj))]
                    (if (and (:masked-group? parent)
                             (= id (first (:shapes parent))))
                      (conj group-ids (:id parent))
                      group-ids)))
                #{}
                ids)

        interacting-shapes
        (filter (fn [shape]
                  ;; If any of the deleted shapes is the destination of
                  ;; some interaction, this must be deleted, too.
                  (let [interactions (:interactions shape)]
                    (some #(and (ctsi/has-destination %)
                                (contains? ids (:destination %)))
                          interactions)))
                (vals objects))

        ;; If any of the deleted shapes is a frame with guides
        guides (into {}
                     (comp (map second)
                           (remove #(contains? ids (:frame-id %)))
                           (map (juxt :id identity)))
                     (dm/get-in page [:options :guides]))

        starting-flows
        (filter (fn [flow]
                  ;; If any of the deleted is a frame that starts a flow,
                  ;; this must be deleted, too.
                  (contains? ids (:starting-frame flow)))
                (-> page :options :flows))

        all-parents
        (reduce (fn [res id]
                  ;; All parents of any deleted shape must be resized.
                  (into res (cph/get-parent-ids objects id)))
                (d/ordered-set)
                ids)

        all-children
        (->> ids ;; Children of deleted shapes must be also deleted.
             (reduce (fn [res id]
                       (into res (cph/get-children-ids objects id)))
                     [])
             (reverse)
             (into (d/ordered-set)))

        find-all-empty-parents
        (fn recursive-find-empty-parents [empty-parents]
          (let [all-ids   (into empty-parents ids)
                contains? (partial contains? all-ids)
                xform     (comp (map lookup)
                                (filter cph/group-shape?)
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
                               (:main-instance? shape))               ;; but check anyway
                        (conj components (:component-id shape))
                        components)))
                  []
                  (into ids all-children))
          [])

        changes (-> (pcb/empty-changes it (:id page))
                    (pcb/with-page page)
                    (pcb/with-objects objects)
                    (pcb/with-library-data file)
                    (pcb/set-page-option :guides guides))

        changes (reduce (fn [changes component-id]
                          ;; It's important to delete the component before the main instance, because we
                          ;; need to store the instance position if we want to restore it later.
                          (pcb/delete-component changes component-id))
                        changes
                        components-to-delete)

        changes (-> changes
                    (pcb/remove-objects all-children)
                    (pcb/remove-objects ids)
                    (pcb/remove-objects empty-parents)
                    (pcb/resize-parents all-parents)
                    (pcb/update-shapes groups-to-unmask
                                       (fn [shape]
                                         (assoc shape :masked-group? false)))
                    (pcb/update-shapes (map :id interacting-shapes)
                                       (fn [shape]
                                         (d/update-when shape :interactions
                                                        (fn [interactions]
                                                          (into []
                                                                (remove #(and (ctsi/has-destination %)
                                                                              (contains? ids (:destination %))))
                                                                interactions)))))
                    (cond-> (seq starting-flows)
                      (pcb/update-page-option :flows (fn [flows]
                                                       (->> (map :id starting-flows)
                                                            (reduce ctp/remove-flow flows))))))
                    undo-id (js/Symbol)]
    (rx/of (dwu/start-undo-transaction undo-id)
           (dc/detach-comment-thread ids)
           (dch/commit-changes changes)
           (ptk/data-event :layout/update all-parents)
           (dwu/commit-undo-transaction undo-id))))

(defn create-and-add-shape
  [type frame-x frame-y {:keys [width height] :as attrs}]
  (ptk/reify ::create-and-add-shape
    ptk/WatchEvent
    (watch [_ state _]
      (let [vbc       (wsh/viewport-center state)
            x         (:x attrs (- (:x vbc) (/ width 2)))
            y         (:y attrs (- (:y vbc) (/ height 2)))
            page-id   (:current-page-id state)
            objects   (wsh/lookup-page-objects state page-id)
            frame-id  (-> (wsh/lookup-page-objects state page-id)
                          (ctst/top-nested-frame {:x frame-x :y frame-y}))

            selected  (wsh/lookup-selected state)
            base      (cph/get-base-shape objects selected)

            parent-id (if (or (and (= 1 (count selected))
                                   (cph/frame-shape? (get objects (first selected))))
                              (empty? selected))
                        frame-id
                        (:parent-id base))

            shape     (cts/setup-shape
                       (-> attrs
                           (assoc :type type)
                           (assoc :x x)
                           (assoc :y y)
                           (assoc :frame-id frame-id)
                           (assoc :parent-id parent-id)))]

        (rx/of (add-shape shape))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Artboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;; FIXME: looks
(defn prepare-create-artboard-from-selection
  [changes id parent-id objects selected index frame-name without-fill?]
  (let [selected-objs (map #(get objects %) selected)
        new-index (or index
                      (cph/get-index-replacement selected objects))]
    (when (d/not-empty? selected)
      (let [srect       (gsh/shapes->rect selected-objs)
            selected-id (first selected)

            frame-id    (dm/get-in objects [selected-id :frame-id])
            parent-id   (or parent-id (dm/get-in objects [selected-id :parent-id]))

            attrs       {:type :frame
                         :x (:x srect)
                         :y (:y srect)
                         :width (:width srect)
                         :height (:height srect)}

            shape     (cts/setup-shape
                       (cond-> attrs
                         (some? id)
                         (assoc :id id)

                         (some? frame-name)
                         (assoc :name frame-name)

                         :always
                         (assoc :frame-id frame-id
                                :parent-id parent-id)

                         :always
                         (with-meta {:index new-index})

                         (or (not= frame-id uuid/zero) without-fill?)
                         (assoc :fills [] :hide-in-viewer true)))

            [shape changes]
            (prepare-add-shape changes shape objects selected)

            changes
            (prepare-move-shapes-into-frame changes (:id shape) selected objects)]

        [shape changes]))))

(defn create-artboard-from-selection
  ([]
   (create-artboard-from-selection nil))
  ([id]
   (create-artboard-from-selection id nil))
  ([id parent-id]
   (create-artboard-from-selection id parent-id nil))
  ([id parent-id index]
   (ptk/reify ::create-artboard-from-selection
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id  (:current-page-id state)
             objects  (wsh/lookup-page-objects state page-id)
             selected (wsh/lookup-selected state)
             selected (cph/clean-loops objects selected)

             changes  (pcb/empty-changes it page-id)

             [frame-shape changes]
             (prepare-create-artboard-from-selection changes
                                                     id
                                                     parent-id
                                                     objects
                                                     selected
                                                     index
                                                     nil
                                                     false)

             undo-id  (js/Symbol)]

         (when changes
           (rx/of
            (dwu/start-undo-transaction undo-id)
            (dch/commit-changes changes)
            (dws/select-shapes (d/ordered-set (:id frame-shape)))
            (ptk/data-event :layout/update [(:id frame-shape)])
            (dwu/commit-undo-transaction undo-id))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape Flags
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-shape-flags
  [ids {:keys [blocked hidden] :as flags}]
  (dm/assert!
   "expected valid coll of uuids"
   (every? uuid? ids))

  (dm/assert!
   "expected valid shape-attrs value for `flags`"
   (cts/valid-shape-attrs? flags))

  (ptk/reify ::update-shape-flags
    ptk/WatchEvent
    (watch [_ state _]
      (let [update-fn
            (fn [obj]
              (cond-> obj
                (boolean? blocked) (assoc :blocked blocked)
                (boolean? hidden) (assoc :hidden hidden)))
            objects (wsh/lookup-page-objects state)
            ;; We have change only the hidden behaviour, to hide only the
            ;; selected shape, block behaviour remains the same.
            ids     (if (boolean? blocked)
                     (into ids (->> ids (mapcat #(cph/get-children-ids objects %))))
                      ids)]
        (rx/of (dch/update-shapes ids update-fn))))))

(defn toggle-visibility-selected
  []
  (ptk/reify ::toggle-visibility-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)]
        (rx/of (dch/update-shapes selected #(update % :hidden not)))))))

(defn toggle-lock-selected
  []
  (ptk/reify ::toggle-lock-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)]
        (rx/of (dch/update-shapes selected #(update % :blocked not)))))))


;; FIXME: this need to be refactored

(defn toggle-file-thumbnail-selected
  []
  (ptk/reify ::toggle-file-thumbnail-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected   (wsh/lookup-selected state)
            pages      (-> state :workspace-data :pages-index vals)]

        (rx/concat
         ;; First: clear the `:use-for-thumbnail?` flag from all not
         ;; selected frames.
         (rx/from
          (->> pages
               (mapcat
                (fn [{:keys [objects id] :as page}]
                  (->> (ctst/get-frames objects)
                       (sequence
                        (comp (filter :use-for-thumbnail?)
                              (map :id)
                              (remove selected)
                              (map (partial vector id)))))))
               (d/group-by first second)
               (map (fn [[page-id frame-ids]]
                      (dch/update-shapes frame-ids #(dissoc % :use-for-thumbnail?) {:page-id page-id})))))

         ;; And finally: toggle the flag value on all the selected shapes
         (rx/of (dch/update-shapes selected #(update % :use-for-thumbnail? not))))))))
