;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.modifiers
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.constraints :as gct]
   [app.common.geom.shapes.layout :as gclo]
   [app.common.geom.shapes.layout-new :as gcln]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]))

;; TODO LAYOUT: ADAPT TO NEW MODIFIERS
(defn set-pixel-precision
  "Adjust modifiers so they adjust to the pixel grid"
  [modifiers shape]

  (if (and (some? (:resize-transform modifiers))
           (not (gmt/unit? (:resize-transform modifiers))))
    ;; If we're working with a rotation we don't handle pixel precision because
    ;; the transformation won't have the precision anyway
    modifiers

    (let [center (gco/center-shape shape)
          base-bounds (-> (:points shape) (gpr/points->rect))

          raw-bounds
          (-> (gtr/transform-bounds (:points shape) center modifiers)
              (gpr/points->rect))

          flip-x? (neg? (get-in modifiers [:resize-vector :x]))
          flip-y? (or (neg? (get-in modifiers [:resize-vector :y]))
                      (neg? (get-in modifiers [:resize-vector-2 :y])))

          path? (= :path (:type shape))
          vertical-line? (and path? (<= (:width raw-bounds) 0.01))
          horizontal-line? (and path? (<= (:height raw-bounds) 0.01))

          target-width (if vertical-line?
                         (:width raw-bounds)
                         (max 1 (mth/round (:width raw-bounds))))

          target-height (if horizontal-line?
                          (:height raw-bounds)
                          (max 1 (mth/round (:height raw-bounds))))

          target-p (cond-> (gpt/round (gpt/point raw-bounds))
                     flip-x?
                     (update :x + target-width)

                     flip-y?
                     (update :y + target-height))

          ratio-width (/ target-width (:width raw-bounds))
          ratio-height (/ target-height (:height raw-bounds))

          modifiers
          (-> modifiers
              (d/without-nils)
              (d/update-in-when
               [:resize-vector :x] #(* % ratio-width))

              ;; If the resize-vector-2 modifier arrives means the resize-vector
              ;; will only resize on the x axis
              (cond-> (nil? (:resize-vector-2 modifiers))
                (d/update-in-when
                 [:resize-vector :y] #(* % ratio-height)))

              (d/update-in-when
               [:resize-vector-2 :y] #(* % ratio-height)))

          origin (get modifiers :resize-origin)
          origin-2 (get modifiers :resize-origin-2)

          resize-v  (get modifiers :resize-vector)
          resize-v-2  (get modifiers :resize-vector-2)
          displacement  (get modifiers :displacement)

          target-p-inv
          (-> target-p
              (gpt/transform
               (cond-> (gmt/matrix)
                 (some? displacement)
                 (gmt/multiply (gmt/inverse displacement))

                 (and (some? resize-v) (some? origin))
                 (gmt/scale (gpt/inverse resize-v) origin)

                 (and (some? resize-v-2) (some? origin-2))
                 (gmt/scale (gpt/inverse resize-v-2) origin-2))))

          delta-v (gpt/subtract target-p-inv (gpt/point base-bounds))

          modifiers
          (-> modifiers
              (d/update-when :displacement #(gmt/multiply (gmt/translate-matrix delta-v) %))
              (cond-> (nil? (:displacement modifiers))
                (assoc :displacement (gmt/translate-matrix delta-v))))]
      modifiers)))


(defn set-children-modifiers
  [modif-tree objects parent ignore-constraints snap-pixel?]
  ;; TODO LAYOUT: SNAP PIXEL!
  (letfn [(set-child [transformed-parent _snap-pixel? modif-tree child]
            (let [modifiers (get-in modif-tree [(:id parent) :modifiers])
                  child-modifiers (gct/calc-child-modifiers parent child modifiers ignore-constraints transformed-parent)
                  ;;child-modifiers (cond-> child-modifiers snap-pixel? (set-pixel-precision child))
                  ]
              (cond-> modif-tree
                (not (ctm/empty-modifiers? child-modifiers))
                (update-in [(:id child) :modifiers :v2] d/concat-vec (:v2 child-modifiers)))))]

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
                  child-modifiers (gcln/normalize-child-modifiers parent child modifiers transformed-parent)]
              (cond-> modif-tree
                (not (ctm/empty-modifiers? child-modifiers))
                (update-in [(:id child) :modifiers :v2] d/concat-vec (:v2 child-modifiers)))))

          (apply-modifiers [modif-tree child]
            (let [modifiers (get-in modif-tree [(:id child) :modifiers])]
              (cond-> child
                (some? modifiers)
                (gtr/transform-shape modifiers)

                (and (nil? modifiers) (group? child))
                (gtr/apply-group-modifiers objects modif-tree))))

          (set-layout-modifiers [parent [layout-line modif-tree] child]
            (let [[modifiers layout-line]
                  (gcln/calc-layout-modifiers parent child layout-line)

                  modif-tree
                  (cond-> modif-tree
                    (d/not-empty? modifiers)
                    (update-in [(:id child) :modifiers :v2] d/concat-vec modifiers))]

              [layout-line modif-tree]))]

    (let [children (map (d/getf objects) (:shapes parent))
          modifiers (get-in modif-tree [(:id parent) :modifiers])
          transformed-parent (gtr/transform-shape parent modifiers)

          modif-tree (reduce (partial normalize-child transformed-parent _snap-pixel?) modif-tree children)

          children (->> children (map (partial apply-modifiers modif-tree)))

          layout-data (gcln/calc-layout-data transformed-parent children)

          children          (into [] (cond-> children (:reverse? layout-data) reverse))

          max-idx           (dec (count children))
          layout-lines      (:layout-lines layout-data)]

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

#_(defn set-layout-modifiers'
  ;; TODO LAYOUT: SNAP PIXEL!
  [modif-tree objects parent _snap-pixel?]
  
  (letfn [(transform-child [child]
            (let [modifiers (get modif-tree (:id child))

                  child
                  (cond-> child
                    (some? modifiers)
                    (-> (merge modifiers) gtr/transform-shape)

                    (and (nil? modifiers) (group? child))
                    (gtr/apply-group-modifiers objects modif-tree))

                  child
                  (-> child
                      (gtr/apply-transform (gmt/transform-in (gco/center-shape parent) (:transform-inverse parent))))]

              child))

          (set-layout-modifiers [parent transform [layout-data modif-tree] child]
            (let [[modifiers layout-data]
                  (gcl/calc-layout-modifiers parent transform child layout-data)

                  modif-tree
                  (cond-> modif-tree
                    (d/not-empty? modifiers)
                    (update-in [(:id child) :modifiers :v2] d/concat-vec modifiers))]

              [layout-data modif-tree]))]

    (let [modifiers         (get modif-tree (:id parent))
          shape             (-> parent (merge modifiers) gtr/transform-shape)
          children          (->> (:shapes shape)
                                 (map (d/getf objects))
                                 (map transform-child))

          center (gco/center-shape shape)
          {:keys [transform transform-inverse]} shape

          shape
          (-> shape
              (gtr/apply-transform (gmt/transform-in center transform-inverse)))

          transformed-rect (:selrect shape)

          layout-data       (gcl/calc-layout-data shape children transformed-rect)
          children          (into [] (cond-> children (:reverse? layout-data) reverse))

          max-idx           (dec (count children))
          layout-lines      (:layout-lines layout-data)]

      (loop [modif-tree modif-tree
             layout-line (first layout-lines)
             pending (rest layout-lines)
             from-idx 0]
        (if (and (some? layout-line) (<= from-idx max-idx))
          (let [to-idx   (+ from-idx (:num-children layout-line))
                children (subvec children from-idx to-idx)

                [_ modif-tree]
                (reduce (partial set-layout-modifiers shape transform) [layout-line modif-tree] children)]
            (recur modif-tree (first pending) (rest pending) to-idx))

          modif-tree)))))

(defn get-first-layout
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
  ;; TODO LAYOUT: Esta ahora puesto al zero pero tiene que mirar todas las raices
  "Given the ids that have changed search for layout roots to recalculate"
  [_ids objects]
  (->> (tree-seq
        #(d/not-empty? (get-in objects [% :shapes]))
        #(get-in objects [% :shapes])
        uuid/zero)

       (map #(get objects %))))

(defn resolve-layout-ids
  "Given a list of ids, resolve the parent layouts that will need to update. This will go upwards
  in the tree while a layout is found"
  [ids objects]

  (into (d/ordered-set)
        (map #(get-first-layout % objects))
        ids))

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

#_(defn modif->js
  [modif-tree objects]
  (clj->js (into {}
                 (map (fn [[k v]]
                        [(get-in objects [k :name]) v]))
                 modif-tree)))

(defn set-objects-modifiers
  [ids objects get-modifier ignore-constraints snap-pixel?]

  (let [set-modifiers
        (fn [modif-tree id]
          (let [shape (get objects id)
                modifiers (cond-> (get-modifier shape) snap-pixel? (set-pixel-precision shape))]
            (-> modif-tree
                (assoc id {:modifiers modifiers}))))

        modif-tree (reduce set-modifiers {} ids)

        shapes-tree (resolve-tree-sequence ids objects)

        modif-tree
        (->> shapes-tree
             (reduce
              (fn [modif-tree shape]
                (let [has-modifiers? (some? (get-in modif-tree [(:id shape) :modifiers]))
                      is-layout? (layout? shape)
                      is-parent? (or (group? shape) (and (frame? shape) (not (layout? shape))))

                      ;; If the current child is inside the layout we ignore the constraints
                      is-inside-layout? (inside-layout? objects shape)]

                  (cond-> modif-tree
                    (and has-modifiers? is-parent?)
                    (set-children-modifiers objects shape (or ignore-constraints is-inside-layout?) snap-pixel?)
                    
                    is-layout?
                    (set-layout-modifiers objects shape snap-pixel?)
                    )))

              modif-tree))]

    ;;(.log js/console ">result" (modif->js modif-tree objects))
    modif-tree))
