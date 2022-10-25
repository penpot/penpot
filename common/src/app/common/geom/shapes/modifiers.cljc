;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.modifiers
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes.constraints :as gct]
   [app.common.geom.shapes.flex-layout :as gcl]
   [app.common.geom.shapes.pixel-precision :as gpp]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]))

(defn set-children-modifiers
  [modif-tree objects parent ignore-constraints snap-pixel?]
  (letfn [(set-child [transformed-parent _snap-pixel? modif-tree child]
            (let [modifiers (get-in modif-tree [(:id parent) :modifiers])
                  child-modifiers (gct/calc-child-modifiers parent child modifiers ignore-constraints transformed-parent)
                  child-modifiers (cond-> child-modifiers snap-pixel? (gpp/set-pixel-precision child))]
              (cond-> modif-tree
                (not (ctm/empty-modifiers? child-modifiers))
                (update-in [(:id child) :modifiers] ctm/add-modifiers child-modifiers))))]
    (let [children (map (d/getf objects) (:shapes parent))
          modifiers (get-in modif-tree [(:id parent) :modifiers])
          transformed-parent (gtr/transform-shape parent modifiers)]
      (reduce (partial set-child transformed-parent snap-pixel?) modif-tree children))))

(defn group? [shape]
  (or (= :group (:type shape))
      (= :bool (:type shape))))

(defn frame? [shape]
  (= :frame (:type shape)))

(defn layout? [shape]
  (and (frame? shape)
       (:layout shape)))

(defn set-layout-modifiers
  ;; TODO LAYOUT: SNAP PIXEL!
  [modif-tree objects parent _snap-pixel?]

  (letfn [(normalize-child [transformed-parent _snap-pixel? modif-tree child]
            (let [modifiers (get-in modif-tree [(:id parent) :modifiers])
                  child-modifiers (gcl/normalize-child-modifiers parent child modifiers transformed-parent)]
              (cond-> modif-tree
                (not (ctm/empty-modifiers? child-modifiers))
                (update-in [(:id child) :modifiers] ctm/add-modifiers child-modifiers))))

          (apply-modifiers [modif-tree child]
            (let [modifiers (get-in modif-tree [(:id child) :modifiers])]
              (cond-> child
                (some? modifiers)
                (gtr/transform-shape modifiers)

                (and (some? modifiers) (group? child))
                (gtr/apply-group-modifiers objects modif-tree))))

          (set-layout-modifiers [parent [layout-line modif-tree] child]
            (let [[modifiers layout-line]
                  (gcl/calc-layout-modifiers parent child layout-line)

                  modif-tree
                  (cond-> modif-tree
                    (d/not-empty? modifiers)
                    (update-in [(:id child) :modifiers] ctm/add-modifiers modifiers))]

              [layout-line modif-tree]))]

    (let [modifiers (get-in modif-tree [(:id parent) :modifiers])
          transformed-parent (gtr/transform-shape parent modifiers)
          children (map (d/getf objects) (:shapes transformed-parent))

          modif-tree   (reduce (partial normalize-child transformed-parent _snap-pixel?) modif-tree children)
          children     (->> children (map (partial apply-modifiers modif-tree)))
          layout-data  (gcl/calc-layout-data transformed-parent children)
          children     (into [] (cond-> children (:reverse? layout-data) reverse))
          max-idx      (dec (count children))

          layout-lines (:layout-lines layout-data)]

      (loop [modif-tree modif-tree
             layout-line (first layout-lines)
             pending (rest layout-lines)
             from-idx 0]
        (if (and (some? layout-line) (<= from-idx max-idx))
          (let [to-idx   (+ from-idx (:num-children layout-line))
                children (subvec children from-idx to-idx)

                [_ modif-tree]
                (reduce (partial set-layout-modifiers transformed-parent) [layout-line modif-tree] children)]
            (recur modif-tree (first pending) (rest pending) to-idx))

          modif-tree)))))

(defn get-tree-root
  [id objects]

  (loop [current id
         result  id]
    (let [shape (get objects current)
          parent (get objects (:parent-id shape))]
      (cond
        (or (not shape) (= uuid/zero current))
        result

        ;; Frame found, but not layout we return the last layout found (or the id)
        (and (= :frame (:type parent))
             (not (:layout parent)))
        result

        ;; Layout found. We continue upward but we mark this layout
        (and (= :frame (:type parent))
             (:layout parent))
        (:id parent)

        ;; If group or boolean or other type of group we continue with the last result
        :else
        (recur (:id parent) result)))))

(defn resolve-tree-sequence
  "Given the ids that have changed search for layout roots to recalculate"
  [modif-tree objects]

  (let [redfn
        (fn [result id]
          (if (= id uuid/zero)
            result
            (let [root (get-tree-root id objects)

                  ;; Remove the children from the current root
                  result
                  (into #{} (remove #(cph/is-child? objects root %)) result)

                  contains-parent?
                  (some #(cph/is-child? objects % root) result)]

              (cond-> result
                (not contains-parent?)
                (conj root)))))

        generate-tree
        (fn [id]
          (->> (tree-seq
                #(d/not-empty? (get-in objects [% :shapes]))
                #(get-in objects [% :shapes])
                id)

               (map #(get objects %))))

        roots (->> modif-tree keys (reduce redfn #{}))]

    (concat
     (when (contains? modif-tree uuid/zero) [(get objects uuid/zero)])
     (mapcat generate-tree roots))))

(defn inside-layout?
  [objects shape]

  (loop [current-id (:id shape)]
    (let [current (get objects current-id)]
      (cond
        (or (nil? current) (= current-id (:parent-id current)))
        false

        (= :frame (:type current))
        (:layout current)

        :else
        (recur (:parent-id current))))))

;;#?(:cljs
;;   (defn modif->js
;;     [modif-tree objects]
;;     (clj->js (into {}
;;                    (map (fn [[k v]]
;;                           [(get-in objects [k :name]) v]))
;;                    modif-tree))))

(defn set-objects-modifiers
  [modif-tree objects ignore-constraints snap-pixel?]

  (let [shapes-tree (resolve-tree-sequence modif-tree objects)

        modif-tree
        (->> shapes-tree
             (reduce
              (fn [modif-tree shape]
                
                (let [root? (= uuid/zero (:id shape))

                      modifiers (get-in modif-tree [(:id shape) :modifiers])
                      modifiers (cond-> modifiers
                                  (and (not root?) (ctm/has-geometry? modifiers) snap-pixel?)
                                  (gpp/set-pixel-precision shape))

                      modif-tree (-> modif-tree (assoc-in [(:id shape) :modifiers] modifiers))
                      
                      has-modifiers? (ctm/child-modifiers? modifiers)
                      is-layout? (layout? shape)
                      is-parent? (or (group? shape) (and (frame? shape) (not (layout? shape))))

                      ;; If the current child is inside the layout we ignore the constraints
                      is-inside-layout? (inside-layout? objects shape)]
                  
                  (cond-> modif-tree
                    (and has-modifiers? is-parent? (not root?))
                    (set-children-modifiers objects shape (or ignore-constraints is-inside-layout?) snap-pixel?)

                    is-layout?
                    (set-layout-modifiers objects shape snap-pixel?))))

              modif-tree))]

    ;;#?(:cljs
    ;;   (.log js/console ">result" (modif->js modif-tree objects)))
    modif-tree))
