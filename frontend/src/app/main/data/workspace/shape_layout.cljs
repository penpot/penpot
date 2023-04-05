; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shape-layout
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctc]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.colors :as cl]
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

(def initial-grid-layout ;; TODO
  {:layout :grid
   :layout-grid-dir        :row
   :layout-gap-type        :multiple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-align-content   :stretch
   :layout-justify-items   :start
   :layout-justify-content :start
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}
   :layout-grid-cells      {}
   :layout-grid-rows       []
   :layout-grid-columns    []})

(defn get-layout-initializer
  [type from-frame?]
  (let [initial-layout-data
        (case type
          :flex initial-flex-layout
          :grid initial-grid-layout)]
    (fn [shape]
      (-> shape
          (merge initial-layout-data)
          (cond-> (= type :grid) ctl/assign-cells)
          ;; If the original shape is not a frame we set clip content and show-viewer to false
          (cond-> (not from-frame?)
            (assoc :show-content true :hide-in-viewer true))))))


(defn shapes->flex-params
  "Given the shapes calculate its flex parameters (horizontal vs vertical, gaps, etc)"
  ([objects shapes]
   (shapes->flex-params objects shapes nil))
  ([objects shapes parent]
   (let [points
         (->> shapes
              (map :id)
              (ctt/sort-z-index objects)
              (map (comp gsh/center-shape (d/getf objects))))

         start (first points)
         end (reduce (fn [acc p] (gpt/add acc (gpt/to-vec start p))) points)

         angle (gpt/signed-angle-with-other
                (gpt/to-vec start end)
                (gpt/point 1 0))

         angle (mod angle 360)

         t1 (min (abs (-  angle 0)) (abs (-  angle 360)))
         t2 (abs (- angle 90))
         t3 (abs (- angle 180))
         t4 (abs (- angle 270))

         tmin (min t1 t2 t3 t4)

         direction
         (cond
           (mth/close? tmin t1) :row
           (mth/close? tmin t2) :column-reverse
           (mth/close? tmin t3) :row-reverse
           (mth/close? tmin t4) :column)

         selrects (->> shapes
                       (mapv :selrect))
         min-x (->> selrects
                    (mapv #(min (:x1 %) (:x2 %)))
                    (apply min))
         max-x (->> selrects
                    (mapv #(max (:x1 %) (:x2 %)))
                    (apply max))
         all-width (->> selrects
                        (map :width)
                        (reduce +))
         column-gap (if (and (> (count shapes) 1)
                             (or (= direction :row) (= direction :row-reverse)))
                      (/ (- (- max-x min-x) all-width)
                         (dec (count shapes)))
                      0)

         min-y (->> selrects
                    (mapv #(min (:y1 %) (:y2 %)))
                    (apply min))
         max-y (->> selrects
                    (mapv #(max (:y1 %) (:y2 %)))
                    (apply max))
         all-height (->> selrects
                         (map :height)
                         (reduce +))
         row-gap (if (and (> (count shapes) 1)
                          (or (= direction :column) (= direction :column-reverse)))
                   (/ (- (- max-y min-y) all-height)
                      (dec (count shapes)))
                   0)

         layout-gap {:row-gap (max row-gap 0) :column-gap (max column-gap 0)}

         parent-selrect (:selrect parent)
         padding (when (and (not (nil? parent)) (> (count shapes) 0))
                   {:p1 (min (- min-y (:y1 parent-selrect)) (- (:y2 parent-selrect) max-y))
                    :p2 (min (- min-x (:x1 parent-selrect)) (- (:x2 parent-selrect) max-x))})]

     (cond-> {:layout-flex-dir direction :layout-gap layout-gap}
       (not (nil? padding))
       (assoc :layout-padding {:p1 (:p1 padding) :p2 (:p2 padding) :p3 (:p1 padding) :p4 (:p2 padding)})))))

(defn shapes->grid-params
  "Given the shapes calculate its flex parameters (horizontal vs vertical, gaps, etc)"
  ([objects shapes]
   (shapes->flex-params objects shapes nil))
  ([_objects _shapes _parent]
   {}))

(defn create-layout-from-id
  [ids type from-frame?]
  (ptk/reify ::create-layout-from-id
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects         (wsh/lookup-page-objects state)
            children-ids    (into [] (mapcat #(get-in objects [% :shapes])) ids)
            children-shapes (map (d/getf objects) children-ids)
            parent          (get objects (first ids))
            layout-params   (when (d/not-empty? children-shapes)
                              (case type
                                :flex (shapes->flex-params objects children-shapes parent)
                                :grid (shapes->grid-params objects children-shapes parent)))
            undo-id         (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes ids (get-layout-initializer type from-frame?))
               (dwc/update-shapes
                ids
                (fn [shape]
                  (-> shape
                      (cond-> (not from-frame?)
                        (assoc :layout-item-h-sizing :auto
                               :layout-item-v-sizing :auto))
                      (merge layout-params))))
               (ptk/data-event :layout/update ids)
               (dwc/update-shapes children-ids #(dissoc % :constraints-h :constraints-v))
               (dwu/commit-undo-transaction undo-id))))))


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
            has-group?      (->> selected-shapes (d/seek cph/group-shape?))
            is-group?       (and single? has-group?)
            has-mask?       (->> selected-shapes (d/seek cph/mask-shape?))
            is-mask?        (and single? has-mask?)
            has-component?  (some true? (map ctc/instance-root? selected-shapes))
            is-component?   (and single? has-component?)]

        (if (and (not is-component?) is-group? (not is-mask?))
          (let [new-shape-id (uuid/next)
                parent-id    (:parent-id (first selected-shapes))
                shapes-ids   (:shapes (first selected-shapes))
                ordered-ids  (into (d/ordered-set) shapes-ids)
                undo-id      (js/Symbol)
                group-index  (cph/get-index-replacement selected objects)]
            (rx/of
             (dwu/start-undo-transaction undo-id)
             (dwse/select-shapes ordered-ids)
             (dws/create-artboard-from-selection new-shape-id parent-id group-index)
             (cl/remove-all-fills [new-shape-id] {:color clr/black
                                                  :opacity 1})
             (create-layout-from-id [new-shape-id] type false)
             (dwc/update-shapes
              [new-shape-id]
              (fn [shape]
                (-> shape
                    (assoc :layout-item-h-sizing :auto
                           :layout-item-v-sizing :auto))))
             ;; Set the children to fixed to remove strange interactions
             (dwc/update-shapes
              selected
              (fn [shape]
                (-> shape
                    (assoc :layout-item-h-sizing :fix
                           :layout-item-v-sizing :fix))))

             (ptk/data-event :layout/update [new-shape-id])
             (dws/delete-shapes page-id selected)
             (dwu/commit-undo-transaction undo-id)))

          (let [new-shape-id (uuid/next)
                undo-id      (js/Symbol)
                flex-params     (shapes->flex-params objects selected-shapes)]
            (rx/of
             (dwu/start-undo-transaction undo-id)
             (dws/create-artboard-from-selection new-shape-id)
             (cl/remove-all-fills [new-shape-id] {:color clr/black
                                                  :opacity 1})
             (create-layout-from-id [new-shape-id] type false)
             (dwc/update-shapes
              [new-shape-id]
              (fn [shape]
                (-> shape
                    (merge flex-params)
                    (assoc :layout-item-h-sizing :auto
                           :layout-item-v-sizing :auto))))
             ;; Set the children to fixed to remove strange interactions
             (dwc/update-shapes
              selected
              (fn [shape]
                (-> shape
                    (assoc :layout-item-h-sizing :fix
                           :layout-item-v-sizing :fix))))

             (ptk/data-event :layout/update [new-shape-id])
             (dwu/commit-undo-transaction undo-id))))))))

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
           (create-layout-from-id [(first selected)] type true)
           (create-layout-from-selection type))
         (dwu/commit-undo-transaction undo-id))))))

(defn toggle-layout-flex
  []
  (ptk/reify ::toggle-layout-flex
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id          (:current-page-id state)
            objects          (wsh/lookup-page-objects state page-id)
            selected         (wsh/lookup-selected state)
            selected-shapes  (map (d/getf objects) selected)
            single?          (= (count selected-shapes) 1)
            has-flex-layout? (and single? (ctl/flex-layout? objects (:id (first selected-shapes))))]

        (when (not= 0 (count selected))
          (if has-flex-layout?
            (rx/of (remove-layout selected))
            (rx/of (create-layout :flex))))))))

(defn update-layout
  [ids changes]
  (ptk/reify ::update-layout
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes ids #(d/deep-merge % changes))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))

#_(defn update-grid-cells
    [parent objects]
    (let [children (cph/get-immediate-children objects (:id parent))
          layout-grid-rows (:layout-grid-rows parent)
          layout-grid-columns (:layout-grid-columns parent)
          num-rows (count layout-grid-columns)
          num-columns (count layout-grid-columns)
          layout-grid-cells (:layout-grid-cells parent)

          allocated-shapes
          (into #{} (mapcat :shapes) (:layout-grid-cells parent))

          no-cell-shapes
          (->> children (:shapes parent) (remove allocated-shapes))

          layout-grid-cells
          (for [[row-idx row] (d/enumerate layout-grid-rows)
                [col-idx col] (d/enumerate layout-grid-columns)]

            (let [shape (nth children (+ (* row-idx num-columns) col-idx) nil)
                  cell-data {:id (uuid/next)
                             :row (inc row-idx)
                             :column (inc col-idx)
                             :row-span 1
                             :col-span 1
                             :shapes (when shape [(:id shape)])}]
              [(:id cell-data) cell-data]))]
      (assoc parent :layout-grid-cells (into {} layout-grid-cells))))

#_(defn check-grid-cells-update
    [ids]
    (ptk/reify ::check-grid-cells-update
      ptk/WatchEvent
      (watch [_ state _]
        (let [objects (wsh/lookup-page-objects state)
              undo-id (js/Symbol)]
          (rx/of (dwc/update-shapes
                  ids
                  (fn [shape]
                    (-> shape
                        (update-grid-cells objects)))))))))

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
                (fn [shape]
                  (case type
                    :row    (ctl/remove-grid-row shape index)
                    :column (ctl/remove-grid-column shape index))))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))

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

  (let [parent (-> (cph/get-parent objects (:id shape))
                   (d/deep-merge parent-changes))

        auto-width? (ctl/auto-width? parent)
        auto-height? (ctl/auto-height? parent)
        col? (ctl/col? parent)
        row? (ctl/row? parent)

        all-children (->> parent
                          :shapes
                          (map (d/getf objects))
                          (remove ctl/layout-absolute?))]

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
  [objects ids-set changes parent]

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
            children-ids (->> ids (mapcat #(cph/get-children-ids objects %)))
            parent-ids (->> ids (map #(cph/get-parent-id objects %)))
            undo-id (js/Symbol)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dwc/update-shapes ids #(d/deep-merge (or % {}) changes))
               (dwc/update-shapes children-ids (partial fix-child-sizing objects changes))
               (dwc/update-shapes parent-ids (partial fix-parent-sizing objects (set ids) changes))
               (ptk/data-event :layout/update ids)
               (dwu/commit-undo-transaction undo-id))))))
