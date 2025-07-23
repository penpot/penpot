;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shape-layout
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.flex-layout :as flex]
   [app.common.geom.shapes.grid-layout :as grid]
   [app.common.logic.libraries :as cll]
   [app.common.types.component :as ctc]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.grid-layout.editor :as dwge]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.selection :as dwse]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def layout-keys
  [:layout
   :layout-flex-dir
   :layout-gap-type
   :layout-gap
   :layout-align-items
   :layout-justify-content
   :layout-align-content
   :layout-wrap-type
   :layout-padding-type
   :layout-padding
   :layout-gap-type])

(def initial-flex-layout
  {:layout                 :flex
   :layout-flex-dir        :row
   :layout-gap-type        :multiple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-content :start
   :layout-align-content   :stretch
   :layout-wrap-type       :nowrap
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}})

(def initial-grid-layout
  {:layout :grid
   :layout-grid-dir        :row
   :layout-gap-type        :multiple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-items   :start
   :layout-align-content   :stretch
   :layout-justify-content :stretch
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}
   :layout-grid-cells      {}
   :layout-grid-rows       []
   :layout-grid-columns    []})

(defn get-layout-initializer
  [type from-frame? calculate-params?]
  (let [[initial-layout-data calculate-params]
        (case type
          :flex [initial-flex-layout flex/calculate-params]
          :grid [initial-grid-layout grid/calculate-params])]

    (fn [shape objects]
      (let [shape
            (-> shape
                (merge initial-layout-data)

                ;; If the original shape is not a frame we set clip content and show-viewer to false
                (cond-> (not from-frame?)
                  (assoc :show-content true :hide-in-viewer true)))

            params (when calculate-params?
                     (calculate-params objects (cfh/get-immediate-children objects (:id shape)) shape))]
        (cond-> (merge shape params)
          (= type :grid)
          (-> (ctl/assign-cells objects) ctl/reorder-grid-children))))))

;; Never call this directly but through the data-event `:layout/update`
;; Otherwise a lot of cycle dependencies could be generated
(defn- update-layout-positions
  [{:keys [page-id ids undo-group]}]
  (ptk/reify ::update-layout-positions
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (or page-id (:current-page-id state))
            objects (dsh/lookup-page-objects state page-id)
            ids (->> ids (filter #(contains? objects %)))]
        (if (d/not-empty? ids)
          (let [modif-tree (dwm/create-modif-tree ids (ctm/reflow-modifiers))]
            (if (features/active-feature? state "render-wasm/v1")
              (rx/of (dwm/apply-wasm-modifiers modif-tree :stack-undo? true :undo-group undo-group))

              (rx/of (dwm/apply-modifiers {:page-id page-id
                                           :modifiers modif-tree
                                           :stack-undo? true
                                           :undo-group undo-group}))))
          (rx/empty))))))

(defn initialize-shape-layout
  []
  (ptk/reify ::initialize-shape-layout
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::finalize-shape-layout) stream)]
        (->> stream
             ;; FIXME: we don't need use types for simple signaling,
             ;; we can just use a keyword for it
             (rx/filter (ptk/type? :layout/update))
             (rx/map deref)
             ;; We buffer the updates to the layout so if there are many changes at the same time
             ;; they are process together. It will get a better performance.
             (rx/buffer-time 100)
             (rx/filter #(d/not-empty? %))
             (rx/mapcat
              (fn [data]
                (->> (group-by :page-id data)
                     (map (fn [[page-id items]]
                            (let [ids (reduce #(into %1 (:ids %2)) #{} items)]
                              (update-layout-positions {:page-id page-id :ids ids})))))))
             (rx/take-until stopper))))))

(defn finalize-shape-layout
  []
  (ptk/data-event ::finalize-shape-layout))

(defn create-layout-from-id
  [id type & {:keys [from-frame? calculate-params?] :or {from-frame? false calculate-params? true}}]
  (dm/assert!
   "expected uuid for `id`"
   (uuid? id))

  (ptk/reify ::create-layout-from-id
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects            (dsh/lookup-page-objects state)
            parent             (get objects id)
            undo-id            (js/Symbol)
            layout-initializer (get-layout-initializer type from-frame? calculate-params?)]

        (rx/of (dwu/start-undo-transaction undo-id)
               (dwsh/update-shapes [id] layout-initializer {:with-objects? true})
               (dwsh/update-shapes (dm/get-prop parent :shapes) #(dissoc % :constraints-h :constraints-v))
               (ptk/data-event :layout/update {:ids [id]})
               (dwu/commit-undo-transaction undo-id))))))

(defn create-layout-from-selection
  [type]
  (ptk/reify ::create-layout-from-selection
    ptk/WatchEvent
    (watch [_ state _]

      (let [page-id         (:current-page-id state)
            objects         (dsh/lookup-page-objects state page-id)
            selected        (dsh/lookup-selected state)
            selected-shapes (map (d/getf objects) selected)
            single?         (= (count selected-shapes) 1)
            has-group?      (->> selected-shapes (d/seek cfh/group-shape?))
            is-group?       (and single? has-group?)
            has-mask?       (->> selected-shapes (d/seek cfh/mask-shape?))
            is-mask?        (and single? has-mask?)
            has-component?  (some true? (map ctc/instance-root? selected-shapes))
            is-component?   (and single? has-component?)
            has-variant?    (some ctc/is-variant? selected-shapes)

            has-layout?     (and single? (ctl/any-layout? (first selected-shapes)))

            new-shape-id (uuid/next)
            undo-id      (js/Symbol)]

        (if has-variant?
          (rx/empty)
          (rx/concat
           (rx/of (dwu/start-undo-transaction undo-id))
           (cond
             (and is-group? (not is-component?) (not is-mask?))
             ;; Create layout from a group:
             ;;  When creating a layout from a group we remove the group and create the layout with its children
             (let [parent-id    (:parent-id (first selected-shapes))
                   shapes-ids   (:shapes (first selected-shapes))
                   ordered-ids  (into (d/ordered-set) shapes-ids)
                   group-index  (cfh/get-index-replacement selected objects)]
               (rx/of
                (dwse/select-shapes ordered-ids)
                (dwsh/create-artboard-from-selection new-shape-id parent-id group-index (:name (first selected-shapes)))
                (cl/remove-all-fills [new-shape-id] {:color clr/black :opacity 1})
                (create-layout-from-id new-shape-id type)
                (dwsh/update-shapes [new-shape-id] #(assoc % :layout-item-h-sizing :auto :layout-item-v-sizing :auto))
                (dwsh/update-shapes selected ctl/toggle-fix-if-auto)
                (dwsh/delete-shapes page-id selected)
                (ptk/data-event :layout/update {:ids [new-shape-id]})
                (dwu/commit-undo-transaction undo-id)))

             has-layout?
             (rx/of
              (create-layout-from-id (first selected) type)
              (ptk/data-event :layout/update {:ids selected})
              (dwu/commit-undo-transaction undo-id))

             ;; Create Layout from selection
             :else
             (rx/of
              (dwsh/create-artboard-from-selection new-shape-id)
              (cl/remove-all-fills [new-shape-id] {:color clr/black :opacity 1})
              (create-layout-from-id new-shape-id type)
              (dwsh/update-shapes [new-shape-id] #(assoc % :layout-item-h-sizing :auto :layout-item-v-sizing :auto))
              (dwsh/update-shapes selected ctl/toggle-fix-if-auto)))

           (rx/of (ptk/data-event :layout/update {:ids [new-shape-id]})
                  (dwu/commit-undo-transaction undo-id))))))))

(defn remove-layout
  [ids]
  (ptk/reify ::remove-shape-layout
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            ids     (->> ids
                         (remove #(->> %
                                       (get objects)
                                       (ctc/is-variant?))))
            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwsh/update-shapes ids #(apply dissoc % layout-keys))
         (ptk/data-event :layout/update {:ids ids})
         (dwu/commit-undo-transaction undo-id))))))

(defn create-layout
  [type]
  (ptk/reify ::create-shape-layout
    ev/Event
    (-data [_]
      {:layout (d/name type)})

    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id          (:current-page-id state)
            objects          (dsh/lookup-page-objects state page-id)
            selected         (dsh/lookup-selected state)
            selected-shapes  (map (d/getf objects) selected)
            single?          (= (count selected-shapes) 1)
            is-frame?        (= :frame (:type (first selected-shapes)))
            has-layout?      (ctl/any-layout? (first selected-shapes))

            undo-id          (js/Symbol)]

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (if (and single? is-frame? (not has-layout?))
           (create-layout-from-id (first selected) type :from-frame? true)
           (create-layout-from-selection type))
         (dwu/commit-undo-transaction undo-id))))))

(defn toggle-layout
  [type]
  (ptk/reify ::toggle-shape-layout
    ptk/WatchEvent
    (watch [it state _]
      (let [objects          (dsh/lookup-page-objects state)
            selected         (dsh/lookup-selected state)
            selected-shapes  (map (d/getf objects) selected)
            single?          (= (count selected-shapes) 1)
            has-layout?      (and single?
                                  (ctl/any-layout? objects (:id (first selected-shapes))))]

        (when (not= 0 (count selected))
          (let [event (if has-layout?
                        (remove-layout selected)
                        (create-layout type))]
            (rx/of (with-meta event (meta it)))))))))

(defn update-layout
  ([ids changes] (update-layout ids changes nil))
  ([ids changes options]
   (ptk/reify ::update-layout
     ptk/WatchEvent
     (watch [_ _ _]
       (let [undo-id       (js/Symbol)
             padding-attrs (-> (get changes :layout-padding)
                               keys
                               set)]
         (rx/of (dwu/start-undo-transaction undo-id)
                (dwsh/update-shapes ids (d/patch-object changes)
                                    (cond-> options
                                      (seq padding-attrs)
                                      (assoc :changed-sub-attr padding-attrs)))
                (ptk/data-event :layout/update {:ids ids})
                (dwu/commit-undo-transaction undo-id)
                (when (or (:layout-align-content changes) (:layout-justify-content changes))
                  (ptk/event ::ev/event
                             {::ev/name "layout-change-alignment"}))
                (when (or (:layout-padding changes) (:layout-gap changes))
                  (ptk/event ::ev/event
                             {::ev/name "layout-change-margin"}))))))))

(defn add-layout-track
  ([ids type value]
   (add-layout-track ids type value nil))
  ([ids type value index]
   (assert (#{:row :column} type))
   (ptk/reify ::add-layout-track
     ptk/WatchEvent
     (watch [_ _ _]
       (let [undo-id (js/Symbol)]
         (rx/of (dwu/start-undo-transaction undo-id)
                (dwsh/update-shapes
                 ids
                 (fn [shape]
                   (case type
                     :row    (ctl/add-grid-row shape value index)
                     :column (ctl/add-grid-column shape value index))))
                (ptk/data-event :layout/update {:ids ids})
                (dwu/commit-undo-transaction undo-id)))))))

(defn remove-layout-track
  [ids type index & {:keys [with-shapes?] :or {with-shapes? false}}]
  (assert (#{:row :column} type))

  (ptk/reify ::remove-layout-track
    ptk/WatchEvent
    (watch [_ state _]
      (let [undo-id (js/Symbol)]
        (let [objects (dsh/lookup-page-objects state)

              shapes-to-delete
              (when with-shapes?
                (->> ids
                     (mapcat
                      (fn [id]
                        (let [shape (get objects id)]
                          (if (= type :column)
                            (ctl/shapes-by-column shape index)
                            (ctl/shapes-by-row shape index)))))
                     (into #{})))]
          (rx/of (dwu/start-undo-transaction undo-id)
                 (if shapes-to-delete
                   (dwsh/delete-shapes shapes-to-delete)
                   (rx/empty))
                 (dwsh/update-shapes
                  ids
                  (fn [shape objects]
                    (case type
                      :row    (ctl/remove-grid-row shape index objects)
                      :column (ctl/remove-grid-column shape index objects)))
                  {:with-objects? true})
                 (ptk/data-event :layout/update {:ids ids})
                 (dwu/commit-undo-transaction undo-id)))))))

(defn duplicate-layout-track
  [ids type index]
  (assert (#{:row :column} type))

  (ptk/reify ::duplicate-layout-track
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id      (:current-file-id state)
            page         (dsh/lookup-page state)
            objects      (:objects page)
            libraries    (dsh/lookup-libraries state)
            library-data (dsh/lookup-file state file-id)
            shape-id     (first ids)
            base-shape   (get objects shape-id)

            shapes-by-track
            (if (= type :column)
              (ctl/shapes-by-column base-shape index false)
              (ctl/shapes-by-row base-shape index false))

            ;; Change to set in order to use auxiliary functions
            selected (set shapes-by-track)

            changes
            (-> (pcb/empty-changes it)
                (cll/generate-duplicate-changes objects page selected (gpt/point 0 0) libraries library-data file-id)
                (cll/generate-duplicate-changes-update-indices objects selected))

            ;; Creates a map with shape-id => duplicated-shape-id
            ids-map
            (->> changes
                 :redo-changes
                 (filter #(= (:type %) :add-obj))
                 (filter #(selected (:old-id %)))
                 (map #(vector (:old-id %) (get-in % [:obj :id])))
                 (into {}))

            changes
            (-> changes
                (pcb/update-shapes
                 ids
                 (fn [shape objects]
                   ;; The duplication could have altered the grid so we restore the values, we'll calculate the good ones now
                   (let [shape (merge shape (select-keys base-shape [:layout-grid-cells :layout-grid-columns :layout-grid-rows]))]
                     (case type
                       :row    (ctl/duplicate-row shape objects index ids-map)
                       :column (ctl/duplicate-column shape objects index ids-map))))
                 {:with-objects? true}))

            undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (ptk/data-event :layout/update {:ids ids})
               (dwu/commit-undo-transaction undo-id))))))

(defn reorder-layout-track
  [ids type from-index to-index move-content?]
  (assert (#{:row :column} type))

  (ptk/reify ::reorder-layout-track
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwsh/update-shapes
                ids
                (fn [shape]
                  (case type
                    :row    (ctl/reorder-grid-row shape from-index to-index move-content?)
                    :column (ctl/reorder-grid-column shape from-index to-index move-content?))))
               (ptk/data-event :layout/update {:ids ids})
               (dwu/commit-undo-transaction undo-id))))))

(defn hover-layout-track
  [ids type index hover?]
  (assert (#{:row :column} type))

  (ptk/reify ::hover-layout-track
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (dsh/lookup-page-objects state)
            shape (get objects (first ids))

            highlighted
            (when hover?
              (->> (if (= type :row)
                     (ctl/shapes-by-row shape index)
                     (ctl/shapes-by-column shape index))
                   (set)))]
        (cond-> state
          hover?
          (update-in [:workspace-grid-edition (first ids) :hover-track] (fnil conj #{}) [type index])

          (not hover?)
          (update-in [:workspace-grid-edition (first ids) :hover-track] (fnil disj #{}) [type index])

          :always
          (assoc-in [:workspace-local :highlighted] highlighted))))))

(defn change-layout-track
  [ids type index props]
  (assert (#{:row :column} type))
  (ptk/reify ::change-layout-track
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)
            property (case type
                       :row :layout-grid-rows
                       :column :layout-grid-columns)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwsh/update-shapes
                ids
                (fn [shape]
                  (-> shape
                      (update-in [property index] merge props))))
               (ptk/data-event :layout/update {:ids ids})
               (dwu/commit-undo-transaction undo-id))))))

(defn fix-child-sizing
  [objects parent-changes shape]

  (let [parent (-> (cfh/get-parent objects (:id shape))
                   (d/deep-merge parent-changes))

        auto-width? (ctl/auto-width? parent)
        auto-height? (ctl/auto-height? parent)
        col? (ctl/col? parent)
        row? (ctl/row? parent)

        all-children (->> parent
                          :shapes
                          (map (d/getf objects))
                          (remove ctl/position-absolute?))]

    (cond-> shape
      ;; If the parent is hug width and the direction column
      ;; change to fixed when ALL children are fill
      (and col? auto-width? (every? ctl/fill-width? all-children))
      (assoc :layout-item-h-sizing :fix)

      ;; If the parent is hug height and the direction is column
      ;; change to fixed when ANY children is fill
      (and col? auto-height? (ctl/fill-height? shape))
      (assoc :layout-item-v-sizing :fix)

      ;; If the parent is hug width and the direction row
      ;; change to fixed when ANY children is fill
      (and row? auto-width? (ctl/fill-width? shape))
      (assoc :layout-item-h-sizing :fix)

      ;; If the parent is hug height and the direction row
      ;; change to fixed when ALL children are fill
      (and row? auto-height? (every? ctl/fill-height? all-children))
      (assoc :layout-item-v-sizing :fix))))

(defn fix-parent-sizing
  [parent objects ids-set changes]

  (let [auto-width? (ctl/auto-width? parent)
        auto-height? (ctl/auto-height? parent)
        col? (ctl/col? parent)
        row? (ctl/row? parent)

        all-children
        (->> parent :shapes
             (map (d/getf objects))
             (map (fn [shape]
                    (if (contains? ids-set (:id shape))
                      (d/deep-merge shape changes)
                      shape))))]

    (cond-> parent
      ;; Col layout and parent is hug-width if all children are fill-width
      ;; change parent to fixed
      (and col? auto-width? (every? ctl/fill-width? all-children))
      (assoc :layout-item-h-sizing :fix)

      ;; Col layout and parent is hug-height if any children is fill-height
      ;; change parent to fixed
      (and col? auto-height? (some ctl/fill-height? all-children))
      (assoc :layout-item-v-sizing :fix)

      ;; Row layout and parent is hug-width if any children is fill-width
      ;; change parent to fixed
      (and row? auto-width? (some ctl/fill-width? all-children))
      (assoc :layout-item-h-sizing :fix)

      ;; Row layout and parent is hug-height if all children are fill-height
      ;; change parent to fixed
      (and row? auto-height? (every? ctl/fill-height? all-children))
      (assoc :layout-item-v-sizing :fix))))

(defn update-layout-child
  ([ids changes] (update-layout-child ids changes nil))
  ([ids changes options]
   (ptk/reify ::update-layout-child
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id      (or (get options :page-id)
                              (get state :current-page-id))
             objects      (dsh/lookup-page-objects state page-id)
             children-ids (->> ids (mapcat #(cfh/get-children-ids objects %)))
             parent-ids   (->> ids (map #(cfh/get-parent-id objects %)))
             undo-id      (js/Symbol)
             margin-attrs (-> (get changes :layout-item-margin)
                              keys
                              set)]
         (rx/of (dwu/start-undo-transaction undo-id)
                (dwsh/update-shapes ids (d/patch-object changes)
                                    (cond-> options
                                      (seq margin-attrs)
                                      (assoc :changed-sub-attr margin-attrs)))
                (dwsh/update-shapes children-ids (partial fix-child-sizing objects changes) options)
                (dwsh/update-shapes
                 parent-ids
                 (fn [parent objects]
                   (-> parent
                       (fix-parent-sizing objects (set ids) changes)
                       (cond-> (ctl/grid-layout? parent)
                         (ctl/assign-cells objects))))
                 (merge options {:with-objects? true}))
                (ptk/data-event :layout/update {:ids ids})
                (dwu/commit-undo-transaction undo-id)))))))

(defn update-grid-cells
  [layout-id ids props]
  (ptk/reify ::update-grid-cells
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwsh/update-shapes
          [layout-id]
          (fn [shape]
            (->> ids
                 (reduce
                  (fn [shape cell-id]
                    (d/update-in-when
                     shape
                     [:layout-grid-cells cell-id]
                     d/patch-object props))
                  shape))))
         (ptk/data-event :layout/update {:ids [layout-id]})
         (dwu/commit-undo-transaction undo-id))))))

(defn change-cells-mode
  [layout-id ids mode]

  (ptk/reify ::change-cells-mode
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwsh/update-shapes
          [layout-id]
          (fn [shape objects]
            (case mode
              :auto
              ;; change the manual cells and move to auto
              (->> ids
                   (reduce
                    (fn [shape cell-id]
                      (let [cell (get-in shape [:layout-grid-cells cell-id])]
                        (cond-> shape
                          (or (contains? #{:area :manual} (:position cell))
                              (> (:row-span cell) 1)
                              (> (:column-span cell) 1))
                          (-> (d/update-in-when [:layout-grid-cells cell-id] assoc :shapes [] :position :auto)
                              (d/update-in-when [:layout-grid-cells cell-id] dissoc :area-name)
                              (ctl/resize-cell-area (:row cell) (:column cell) (:row cell) (:column cell) 1 1)
                              (ctl/assign-cells objects)))))
                    shape))

              :manual
              (->> ids
                   (reduce
                    (fn [shape cell-id]
                      (let [cell (get-in shape [:layout-grid-cells cell-id])]
                        (cond-> shape
                          (contains? #{:area :auto} (:position cell))
                          (-> (d/assoc-in-when [:layout-grid-cells cell-id :position] :manual)
                              (d/update-in-when [:layout-grid-cells cell-id] dissoc :area-name)
                              (ctl/assign-cells objects)))))
                    shape))

              :area
              ;; Create area with the selected cells
              (let [{:keys [first-row first-column last-row last-column]}
                    (ctl/cells-coordinates (->> ids (map #(get-in shape [:layout-grid-cells %]))))

                    target-cell
                    (ctl/get-cell-by-position shape first-row first-column)

                    shape
                    (-> shape
                        (ctl/resize-cell-area
                         (:row target-cell) (:column target-cell)
                         first-row
                         first-column
                         (inc (- last-row first-row))
                         (inc (- last-column first-column)))
                        (ctl/assign-cells objects))]

                (-> shape
                    (d/update-in-when [:layout-grid-cells (:id target-cell)] assoc :position :area)))))
          {:with-objects? true})
         (dwge/clean-selection layout-id)
         (ptk/data-event :layout/update {:ids [layout-id]})
         (dwu/commit-undo-transaction undo-id))))))

(defn merge-cells
  [layout-id ids]

  (ptk/reify ::merge-cells
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwsh/update-shapes
          [layout-id]
          (fn [shape objects]
            (let [cells (->> ids (map #(get-in shape [:layout-grid-cells %])))

                  {:keys [first-row first-column last-row last-column]}
                  (ctl/cells-coordinates cells)

                  target-cell
                  (ctl/get-cell-by-position shape first-row first-column)]
              (-> shape
                  (ctl/resize-cell-area
                   (:row target-cell) (:column target-cell)
                   first-row
                   first-column
                   (inc (- last-row first-row))
                   (inc (- last-column first-column)))
                  (ctl/assign-cells objects))))
          {:with-objects? true})
         (dwge/clean-selection layout-id)
         (ptk/data-event :layout/update {:ids [layout-id]})
         (dwu/commit-undo-transaction undo-id))))))

(defn update-grid-cell-position
  [layout-id cell-id props]

  (ptk/reify ::update-grid-cell-position
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwsh/update-shapes
          [layout-id]
          (fn [shape objects]
            (let [prev-data (-> (dm/get-in shape [:layout-grid-cells cell-id])
                                (select-keys [:row :column :row-span :column-span]))

                  new-data (merge prev-data props)]
              (-> shape
                  (ctl/resize-cell-area (:row prev-data) (:column prev-data)
                                        (:row new-data) (:column new-data)
                                        (:row-span new-data) (:column-span new-data))
                  (ctl/assign-cells objects))))
          {:with-objects? true})
         (ptk/data-event :layout/update {:ids [layout-id]})
         (dwu/commit-undo-transaction undo-id))))))


(defn create-cell-board
  [layout-id cell-ids]
  (ptk/reify ::create-cell-board
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state)
            frame-id (uuid/next)

            undo-id (js/Symbol)

            shape (get objects layout-id)
            cells      (->> cell-ids (map #(get-in shape [:layout-grid-cells %])))
            selected   (into #{} (mapcat :shapes) cells)

            {:keys [first-row first-column last-row last-column]} (ctl/cells-coordinates cells)

            target-cell (ctl/get-cell-by-position shape first-row first-column)

            [_ changes]
            (-> (pcb/empty-changes it page-id)
                (pcb/with-objects objects)
                (cond-> (d/not-empty? selected)
                  (cfsh/prepare-create-artboard-from-selection
                   frame-id layout-id objects selected 0 nil true (:id target-cell)))

                (cond-> (empty? (seq selected))
                  (cfsh/prepare-create-empty-artboard
                   frame-id layout-id objects 0 nil true (:id target-cell))))

            changes
            (-> changes
                (pcb/update-shapes
                 [frame-id]
                 (fn [shape]
                   (-> shape
                       (assoc :layout-item-h-sizing :fill)
                       (assoc :layout-item-v-sizing :fill))))
                (pcb/update-shapes
                 [layout-id]
                 (fn [shape]
                   (let [new-row-span (inc (- last-row first-row))
                         new-col-span (inc (- last-column first-column))]
                     (-> shape
                         (ctl/resize-cell-area
                          (:row target-cell) (:column target-cell)
                          first-row first-column new-row-span new-col-span))))))]

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (ptk/data-event :layout/update {:ids [layout-id]})
         (dwu/commit-undo-transaction undo-id))))))
