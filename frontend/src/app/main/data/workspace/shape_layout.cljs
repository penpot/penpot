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
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes.flex-layout :as flex]
   [app.common.geom.shapes.grid-layout :as grid]
   [app.common.types.component :as ctc]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.grid-layout.editor :as dwge]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.selection :as dwse]
   [app.main.data.workspace.shapes :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.core :as rx]
   [potok.core :as ptk]))

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
  [type from-frame? objects]
  (let [[initial-layout-data calculate-params]
        (case type
          :flex [initial-flex-layout flex/calculate-params]
          :grid [initial-grid-layout grid/calculate-params])]

    (fn [shape]
      (let [shape
            (-> shape
                (merge initial-layout-data)

                ;; If the original shape is not a frame we set clip content and show-viewer to false
                (cond-> (not from-frame?)
                  (assoc :show-content true :hide-in-viewer true)))

            params (calculate-params objects (cfh/get-immediate-children objects (:id shape)) shape)]
        (cond-> (merge shape params)
          (= type :grid) (-> (ctl/assign-cells objects) ctl/reorder-grid-children))))))

;; Never call this directly but through the data-event `:layout/update`
;; Otherwise a lot of cycle dependencies could be generated
(defn- update-layout-positions
  [ids]
  (ptk/reify ::update-layout-positions
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            ids (->> ids (filter #(contains? objects %)))]
        (if (d/not-empty? ids)
          (let [modif-tree (dwm/create-modif-tree ids (ctm/reflow-modifiers))]
            (rx/of (dwm/apply-modifiers {:modifiers modif-tree
                                         :stack-undo? true})))
          (rx/empty))))))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::finalize) stream)]
        (->> stream
             (rx/filter (ptk/type? :layout/update))
             (rx/map deref)
             (rx/map #(update-layout-positions %))
             (rx/take-until stopper))))))

(defn finalize
  []
  (ptk/reify ::finalize))

(defn create-layout-from-id
  [id type from-frame?]
  (assert (uuid? id) (str id))
  (ptk/reify ::create-layout-from-id
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects            (wsh/lookup-page-objects state)
            parent             (get objects id)
            undo-id            (js/Symbol)
            layout-initializer (get-layout-initializer type from-frame? objects)]

        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes [id] layout-initializer)
               (dwc/update-shapes (dm/get-prop parent :shapes) #(dissoc % :constraints-h :constraints-v))
               (ptk/data-event :layout/update [id])
               (dwu/commit-undo-transaction undo-id))))))

(defn create-layout-from-selection
  [type]
  (ptk/reify ::create-layout-from-selection
    ptk/WatchEvent
    (watch [_ state _]

      (let [page-id         (:current-page-id state)
            objects         (wsh/lookup-page-objects state page-id)
            selected        (wsh/lookup-selected state)
            selected-shapes (map (d/getf objects) selected)
            single?         (= (count selected-shapes) 1)
            has-group?      (->> selected-shapes (d/seek cfh/group-shape?))
            is-group?       (and single? has-group?)
            has-mask?       (->> selected-shapes (d/seek cfh/mask-shape?))
            is-mask?        (and single? has-mask?)
            has-component?  (some true? (map ctc/instance-root? selected-shapes))
            is-component?   (and single? has-component?)

            new-shape-id (uuid/next)
            undo-id      (js/Symbol)]

        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (if (and is-group? (not is-component?) (not is-mask?))
           ;; Create layout from a group:
           ;;  When creating a layout from a group we remove the group and create the layout with its children
           (let [parent-id    (:parent-id (first selected-shapes))
                 shapes-ids   (:shapes (first selected-shapes))
                 ordered-ids  (into (d/ordered-set) shapes-ids)
                 group-index  (cfh/get-index-replacement selected objects)]
             (rx/of
              (dwse/select-shapes ordered-ids)
              (dws/create-artboard-from-selection new-shape-id parent-id group-index)
              (cl/remove-all-fills [new-shape-id] {:color clr/black :opacity 1})
              (create-layout-from-id new-shape-id type false)
              (dwc/update-shapes [new-shape-id] #(assoc % :layout-item-h-sizing :auto :layout-item-v-sizing :auto))
              (dwc/update-shapes selected #(assoc % :layout-item-h-sizing :fix :layout-item-v-sizing :fix))
              (dws/delete-shapes page-id selected)
              (ptk/data-event :layout/update [new-shape-id])
              (dwu/commit-undo-transaction undo-id)))

           ;; Create Layout from selection
           (rx/of
            (dws/create-artboard-from-selection new-shape-id)
            (cl/remove-all-fills [new-shape-id] {:color clr/black :opacity 1})
            (create-layout-from-id new-shape-id type false)
            (dwc/update-shapes [new-shape-id] #(assoc % :layout-item-h-sizing :auto :layout-item-v-sizing :auto))
            (dwc/update-shapes selected #(assoc % :layout-item-h-sizing :fix :layout-item-v-sizing :fix))))

         (rx/of (ptk/data-event :layout/update [new-shape-id])
                (dwu/commit-undo-transaction undo-id)))))))

(defn remove-layout
  [ids]
  (ptk/reify ::remove-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwc/update-shapes ids #(apply dissoc % layout-keys))
         (ptk/data-event :layout/update ids)
         (dwu/commit-undo-transaction undo-id))))))

(defn create-layout
  [type]
  (ptk/reify ::create-layout
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id          (:current-page-id state)
            objects          (wsh/lookup-page-objects state page-id)
            selected         (wsh/lookup-selected state)
            selected-shapes  (map (d/getf objects) selected)
            single?          (= (count selected-shapes) 1)
            is-frame?        (= :frame (:type (first selected-shapes)))
            undo-id          (js/Symbol)]

        (rx/of
         (dwu/start-undo-transaction undo-id)
         (if (and single? is-frame?)
           (create-layout-from-id (first selected) type true)
           (create-layout-from-selection type))
         (dwu/commit-undo-transaction undo-id))))))

(defn toggle-layout
  [type]
  (ptk/reify ::toggle-layout-flex
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects          (wsh/lookup-page-objects state)
            selected         (wsh/lookup-selected state)
            selected-shapes  (map (d/getf objects) selected)
            single?          (= (count selected-shapes) 1)
            has-layout? (and single? (ctl/any-layout? objects (:id (first selected-shapes))))]

        (when (not= 0 (count selected))
          (if has-layout?
            (rx/of (remove-layout selected))
            (rx/of (create-layout type))))))))

(defn update-layout
  [ids changes]
  (ptk/reify ::update-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes ids (d/patch-object changes))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))

(defn add-layout-track
  [ids type value]
  (assert (#{:row :column} type))
  (ptk/reify ::add-layout-column
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes
                ids
                (fn [shape]
                  (case type
                    :row    (ctl/add-grid-row shape value)
                    :column (ctl/add-grid-column shape value))))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))

(defn remove-layout-track
  [ids type index]
  (assert (#{:row :column} type))

  (ptk/reify ::remove-layout-column
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes
                ids
                (fn [shape objects]
                  (case type
                    :row    (ctl/remove-grid-row shape index objects)
                    :column (ctl/remove-grid-column shape index objects))))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))

(defn reorder-layout-track
  [ids type from-index to-index move-content?]
  (assert (#{:row :column} type))

  (ptk/reify ::reorder-layout-track
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes
                ids
                (fn [shape]
                  (case type
                    :row    (ctl/reorder-grid-row shape from-index to-index move-content?)
                    :column (ctl/reorder-grid-column shape from-index to-index move-content?))))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))

(defn hover-layout-track
  [ids type index hover?]
  (assert (#{:row :column} type))

  (ptk/reify ::hover-layout-track
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (wsh/lookup-page-objects state)
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
               (dwc/update-shapes
                ids
                (fn [shape]
                  (-> shape
                      (update-in [property index] merge props))))
               (ptk/data-event :layout/update ids)
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
  [ids changes]
  (ptk/reify ::update-layout-child
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            children-ids (->> ids (mapcat #(cfh/get-children-ids objects %)))
            parent-ids (->> ids (map #(cfh/get-parent-id objects %)))
            undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes ids (d/patch-object changes))
               (dwc/update-shapes children-ids (partial fix-child-sizing objects changes))
               (dwc/update-shapes
                parent-ids
                (fn [parent objects]
                  (-> parent
                      (fix-parent-sizing objects (set ids) changes)
                      (cond-> (ctl/grid-layout? parent)
                        (ctl/assign-cells objects)))))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))

(defn update-grid-cells
  [layout-id ids props]
  (ptk/reify ::update-grid-cells
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)

         (dwc/update-shapes
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
         (ptk/data-event :layout/update [layout-id])
         (dwu/commit-undo-transaction undo-id))))))

(defn change-cells-mode
  [layout-id ids mode]

  (ptk/reify ::change-cells-mode
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwc/update-shapes
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
                    (d/update-in-when [:layout-grid-cells (:id target-cell)] assoc :position :area))))))
         (dwge/clean-selection layout-id)
         (ptk/data-event :layout/update [layout-id])
         (dwu/commit-undo-transaction undo-id))))))

(defn update-grid-cell-position
  [layout-id cell-id props]

  (ptk/reify ::update-grid-cell-position
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwc/update-shapes
          [layout-id]
          (fn [shape]
            (let [prev-data (-> (dm/get-in shape [:layout-grid-cells cell-id])
                                (select-keys [:row :column :row-span :column-span]))

                  new-data (merge prev-data props)]
              (-> shape
                  (ctl/resize-cell-area (:row prev-data) (:column prev-data)
                                        (:row new-data) (:column new-data)
                                        (:row-span new-data) (:column-span new-data))
                  (ctl/assign-cells objects)))))
         (ptk/data-event :layout/update [layout-id])
         (dwu/commit-undo-transaction undo-id))))))
