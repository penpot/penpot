;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.grid
  "Independent cell expectations. No production grid assignment or direction
   helper supplies expected values.")

(defn get-cells
  [shape]
  (->> (vals (:layout-grid-cells shape))
       (sort-by (juxt :row :column))
       (mapv #(dissoc % :id))))

(defn remap-groups
  [groups ids-map]
  (cond-> groups
    (:layout-grid-cells groups)
    (update-in [:layout-grid-cells :layout-grid-cells]
               (fn [cells]
                 (mapv #(update % :shapes (fn [ids] (mapv (fn [id] (get ids-map id id)) ids))) cells)))))

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

(defn direction-cells
  [shape direction]
  (let [cells    (get-cells shape)
        order    (fn [dir] (if (= dir :column) (juxt :column :row) (juxt :row :column)))
        eligible (fn [cell] (and (= :auto (:position cell)) (= 1 (:row-span cell) (:column-span cell))))
        old      (filter eligible (sort-by (order (:layout-grid-dir shape)) cells))
        new      (filter eligible (sort-by (order direction) cells))
        members  (vec (mapcat :shapes old))
        by-cell  (into {} (map-indexed (fn [index cell]
                                         [[(:row cell) (:column cell)]
                                          (if-let [id (nth members index nil)] [id] [])]) new))]
    (if (= (or (:layout-grid-dir shape) :row) direction)
      cells
      (mapv (fn [cell]
              (if-let [ids (get by-cell [(:row cell) (:column cell)])]
                (assoc cell :shapes ids)
                cell)) cells))))

(defn get-intended-values
  [shape action value]
  (let [before (get-cells shape)
        cells  (case action
                 :direction (direction-cells shape value)
                 :cell (mapv #(if (= [(:row value) (:column value)] [(:row %) (:column %)])
                                (merge % (:props value)) %) before))]
    (cond-> {:layout-grid-cells cells}
      (= action :direction) (assoc :layout-grid-dir value))))

(defn edit
  [model shape action value]
  (if (:shape-ref shape)
    (reduce-kv (fn [model key value]
                 (if (= value (if (= key :layout-grid-cells) (get-cells shape) (get shape key)))
                   model
                   (assoc-in model [(:id shape) key] {key value})))
               model (get-intended-values shape action value))
    model))

(defn settle-cells
  "Drop departed/absolute children; fill existing empty cells with participating
   children in parent order. A gap, manual cell or span ends the auto prefix."
  [cells shape objects]
  (let [order         (if (= :column (:layout-grid-dir shape)) (juxt :column :row) (juxt :row :column))
        participating (filterv #(let [child (get objects %)]
                                  (not (or (:hidden child) (:layout-item-absolute child)))) (:shapes shape))
        members       (set participating)
        prefix        (fn [cells]
                        (second
                         (reduce (fn [[ended result] cell]
                                   (let [manual (or (= :manual (:position cell))
                                                    (not= 1 (:row-span cell) (:column-span cell))
                                                    (and ended (seq (:shapes cell)) (= :auto (:position cell))))]
                                     [(or ended manual (empty? (:shapes cell)))
                                      (conj result (cond-> cell
                                                     (and manual (= :auto (:position cell))) (assoc :position :manual)))]))
                                 [false []] cells)))
        cells         (->> cells (sort-by order)
                           (mapv #(update % :shapes (fn [ids] (filterv members ids)))) prefix)
        assigned      (set (mapcat :shapes cells))
        missing       (remove assigned participating)
        [cells _]     (reduce (fn [[result pending] cell]
                                (if (and (seq pending) (empty? (:shapes cell)))
                                  [(conj result (assoc cell :position :auto :shapes [(first pending)])) (rest pending)]
                                  [(conj result cell) pending]))
                              [[] missing] cells)]
    (->> cells prefix (sort-by (juxt :row :column)) vec)))
