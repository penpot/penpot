;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.grid
  "Independent cell expectations. No production grid assignment or direction
   helper supplies expected values.")

(defn get-context
  [shape]
  {:rows (count (:layout-grid-rows shape))
   :columns (count (:layout-grid-columns shape))
   :direction (or (:layout-grid-dir shape) :row)})

(defn get-cells
  [shape]
  (with-meta (->> (vals (:layout-grid-cells shape))
                  (sort-by (juxt :row :column))
                  (mapv #(dissoc % :id)))
    {::context (get-context shape)}))

(defn remap-groups
  [groups ids-map]
  (cond-> groups
    (:layout-grid-cells groups)
    (update-in [:layout-grid-cells :layout-grid-cells]
               (fn [cells]
                 (with-meta (mapv #(update % :shapes (fn [ids] (mapv (fn [id] (get ids-map id id)) ids))) cells)
                   (meta cells))))))

(defn get-child-map
  "Pair identities through refs, never through resulting cell membership."
  [objects source target]
  (let [source-ids (set (:shapes source))
        target-ids (set (:shapes target))
        find-ref   (fn [ids id]
                     (loop [candidate id seen #{}]
                       (cond
                         (contains? ids candidate) candidate
                         (or (nil? candidate) (contains? seen candidate)) nil
                         :else (recur (:shape-ref (get objects candidate)) (conj seen candidate)))))
        forward    (into {} (keep #(when-let [id (find-ref target-ids %)] [% id])) (:shapes source))
        backward   (into {} (keep #(when-let [id (find-ref source-ids %)] [id %])) (:shapes target))]
    (merge forward backward)))

(defn- flow-order
  "The order in which a grid of `direction` flows its auto cells."
  [direction]
  (if (= direction :column) (juxt :column :row) (juxt :row :column)))

(defn- fix-after-gap
  "`cells`, in flow order, with each auto cell that holds a shape after an
   empty, manual or spanning one made manual, as the layout keeps it."
  [cells]
  (second
   (reduce (fn [[ended result] cell]
             (let [manual (or (= :manual (:position cell))
                              (not= 1 (:row-span cell) (:column-span cell))
                              (and ended (seq (:shapes cell)) (= :auto (:position cell))))]
               [(or ended manual (empty? (:shapes cell)))
                (conj result (cond-> cell
                               (and manual (= :auto (:position cell))) (assoc :position :manual)))]))
           [false []] cells)))

(defn direction-cells
  [shape direction]
  (let [cells    (get-cells shape)
        eligible (fn [cell] (and (= :auto (:position cell)) (= 1 (:row-span cell) (:column-span cell))))
        old      (filter eligible (sort-by (flow-order (:layout-grid-dir shape)) cells))
        new      (filter eligible (sort-by (flow-order direction) cells))
        members  (vec (mapcat :shapes old))
        by-cell  (into {} (map-indexed (fn [index cell]
                                         [[(:row cell) (:column cell)]
                                          (if-let [id (nth members index nil)] [id] [])]) new))]
    (if (= (or (:layout-grid-dir shape) :row) direction)
      cells
      (with-meta (mapv (fn [cell]
                         (if-let [ids (get by-cell [(:row cell) (:column cell)])]
                           (assoc cell :shapes ids)
                           cell)) cells)
        (meta cells)))))

(defn get-intended-values
  [shape action value]
  (let [before (get-cells shape)
        cells  (case action
                 :direction (direction-cells shape value)
                 :cell (mapv #(if (= [(:row value) (:column value)] [(:row %) (:column %)])
                                (merge % (:props value)) %) before))
        ;; the edit reflows the grid, which keeps cells after a gap in place
        cells  (->> cells
                    (sort-by (flow-order (if (= action :direction) value (:layout-grid-dir shape))))
                    fix-after-gap
                    (sort-by (juxt :row :column))
                    vec)]
    (cond-> {:layout-grid-cells (with-meta cells
                                  {::context (cond-> (get-context shape)
                                               (= action :direction) (assoc :direction value))})}
      (= action :direction) (assoc :layout-grid-dir value))))

(defn edit
  [model shape action value]
  (if (:shape-ref shape)
    (let [intended (get-intended-values shape action value)
          ;; a direction change authors the cells only when it moves auto
          ;; items itself; the reflow after it derives the rest
          derived? (and (= action :direction)
                        (= (direction-cells shape value) (get-cells shape)))]
      (reduce-kv (fn [model key value]
                   (if (= value (if (= key :layout-grid-cells) (get-cells shape) (get shape key)))
                     model
                     (assoc-in model [(:id shape) key] {key value})))
                 model (cond-> intended derived? (dissoc :layout-grid-cells))))
    model))

(defn- extend-cells
  [cells before after]
  (into cells
        (for [row (range 1 (inc (:rows after)))
              column (range 1 (inc (:columns after)))
              :when (or (> row (:rows before)) (> column (:columns before)))
              :when (not-any? #(and (<= (:row %) row (+ (:row %) (:row-span %) -1))
                                    (<= (:column %) column (+ (:column %) (:column-span %) -1))) cells)]
          {:row row :column column :row-span 1 :column-span 1 :position :auto
           :align-self :auto :justify-self :auto :shapes []})))

(defn flow-shapes
  "The children of the grid `shape` as `:shapes` lists a grid that orders
   them by the flow of its cells: in reverse flow order, with hidden and
   absolute children, which hold no cell, in their places."
  [shape]
  (let [in-cell (into [] (comp (keep (comp first :shapes))
                               (filter (set (:shapes shape)))
                               (distinct))
                      (sort-by (flow-order (:layout-grid-dir shape)) (vals (:layout-grid-cells shape))))
        placed  (set in-cell)]
    (first (reduce (fn [[result pending] id]
                     (if (contains? placed id)
                       [(conj result (first pending)) (rest pending)]
                       [(conj result id) pending]))
                   [[] (reverse in-cell)]
                   (:shapes shape)))))

(defn matches-context?
  [shape cells]
  (if-let [{:keys [rows columns] :as context} (::context (meta cells))]
    (and (= context (get-context shape))
         (every? #(and (<= 1 (:row %) (+ (:row %) (:row-span %) -1) rows)
                       (<= 1 (:column %) (+ (:column %) (:column-span %) -1) columns)) cells))
    true))

(defn settle-cells
  "Predict inherited and automatic track growth from prior cells, reference
   context and child identities. Result cell values never supply expectations.
   Children without a cell take free cells in the order of their main shapes
   in `source`, as sync adds them, whatever order the copy gives them after."
  ([cells shape objects] (settle-cells cells shape objects nil nil))
  ([cells shape objects reference] (settle-cells cells shape objects reference nil))
  ([cells shape objects reference source]
   (let [prior         (::context (meta cells))
         participating (filterv #(let [child (get objects %)]
                                   (not (or (:hidden child) (:layout-item-absolute child)))) (:shapes shape))
         members       (set participating)
         trimmed       (mapv #(update % :shapes (fn [ids] (filterv members ids))) cells)
         assigned      (set (mapcat :shapes trimmed))
         missing       (remove assigned participating)
         ;; A pending/unaccepted source can be ahead of this copy. Use its
         ;; context once track writes or added children have reached the copy.
         context       (if (and prior reference
                                (or (not= prior (get-context shape))
                                    (> (count missing) (count (filter #(empty? (:shapes %)) trimmed)))))
                         reference prior)
         cells         (if context (extend-cells trimmed prior context) trimmed)
         order         (flow-order (or (:direction context) (:layout-grid-dir shape)))
         cells         (->> cells (sort-by order) fix-after-gap)
         assigned      (set (mapcat :shapes cells))
         rank          (into {} (map-indexed (fn [i id] [id i])) (:shapes source))
         missing       (sort-by #(get rank (:shape-ref (get objects %)) ##Inf)
                                (remove assigned participating))
         free          (count (filter #(empty? (:shapes %)) cells))
         minimum       (when context (cond-> context
                                       (> (count missing) free)
                                       (-> (update :rows max 1) (update :columns max 1))))
         cells         (if minimum (extend-cells cells context minimum) cells)
         free          (count (filter #(empty? (:shapes %)) cells))
         deficit       (- (count missing) free)
         grown         (when minimum
                         (cond-> minimum
                           (and (pos? deficit) (= :row (:direction minimum)))
                           (update :columns + (quot (+ deficit (:rows minimum) -1) (:rows minimum)))
                           (and (pos? deficit) (= :column (:direction minimum)))
                           (update :rows + (quot (+ deficit (:columns minimum) -1) (:columns minimum)))))
         cells         (if grown (extend-cells cells minimum grown) cells)
         [cells _]     (reduce (fn [[result pending] cell]
                                 (if (and (seq pending) (empty? (:shapes cell)))
                                   [(conj result (assoc cell :position :auto :shapes [(first pending)])) (rest pending)]
                                   [(conj result cell) pending]))
                               [[] missing] (sort-by order cells))]
     (with-meta (->> cells fix-after-gap (sort-by (juxt :row :column)) vec)
       (cond-> (meta cells) grown (assoc ::context grown))))))
