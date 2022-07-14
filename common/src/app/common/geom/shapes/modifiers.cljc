;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.modifiers
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.constraints :as gct]
   [app.common.geom.shapes.layout :as gcl]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]))

(defn set-pixel-precision
  "Adjust modifiers so they adjust to the pixel grid"
  [modifiers shape]

  (if (some? (:resize-transform modifiers))
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
  [modif-tree shape objects ignore-constraints snap-pixel?]
  (letfn [(set-child [transformed-rect snap-pixel? modif-tree child]
            (let [modifiers (get-in modif-tree [(:id shape) :modifiers])
                  child-modifiers (gct/calc-child-modifiers shape child modifiers ignore-constraints transformed-rect)
                  child-modifiers (cond-> child-modifiers snap-pixel? (set-pixel-precision child))]
              (cond-> modif-tree
                (not (gtr/empty-modifiers? child-modifiers))
                (update-in [(:id child) :modifiers] #(merge child-modifiers %)))))]
    (let [children (map (d/getf objects) (:shapes shape))
          modifiers (get-in modif-tree [(:id shape) :modifiers])
          transformed-rect (gtr/transform-selrect (:selrect shape) modifiers)
          resize-modif? (or (:resize-vector modifiers) (:resize-vector-2 modifiers))]
      (reduce (partial set-child transformed-rect (and snap-pixel? resize-modif?)) modif-tree children))))

(defn group? [shape]
  (or (= :group (:type shape))
      (= :bool (:type shape))))

(defn merge-modifiers
  [modif-tree ids modifiers]
  (reduce
   (fn [modif-tree id]
     (update-in modif-tree [id :modifiers] #(merge % modifiers)))
   modif-tree
   ids))

(defn set-layout-modifiers
  [modif-tree objects id]

  (letfn [(transform-child [parent child]
            (let [modifiers (get modif-tree (:id child))

                  child
                  (cond-> child
                    (not (group? child))
                    (-> (merge modifiers) gtr/transform-shape)

                    (group? child)
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
                    (not (gtr/empty-modifiers? modifiers))
                    (merge-modifiers [(:id child)] modifiers)

                    (and (not (gtr/empty-modifiers? modifiers)) (group? child))
                    (merge-modifiers (:shapes child) modifiers))]

              [layout-data modif-tree]))]

    (let [modifiers         (get modif-tree id)

          shape             (-> (get objects id) (merge modifiers) gtr/transform-shape)


          children          (->> (:shapes shape)
                                 (map (d/getf objects))
                                 (map (partial transform-child shape)))

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

(defn resolve-layout-ids
  "Given a list of ids, resolve the parent layouts that will need to update. This will go upwards
  in the tree while a layout is found"
  [ids objects]

  (into (d/ordered-set)
        (map #(get-first-layout % objects))
        ids))

(defn set-objects-modifiers
  [ids objects get-modifier ignore-constraints snap-pixel?]

  (let [set-modifiers
        (fn [modif-tree id]
          (assoc modif-tree id {:modifiers (get-modifier (get objects id))}))

        modif-tree (reduce set-modifiers {} ids)

        ids (resolve-layout-ids ids objects)

        ;; First: Calculate children modifiers (constraints, etc)
        [modif-tree touched-layouts]
        (loop [current         (first ids)
               pending         (rest ids)
               modif-tree      modif-tree
               touched-layouts (d/ordered-set)]
          (if (some? current)
            (let [shape (get objects current)
                  pending (concat pending (:shapes shape))

                  touched-layouts
                  (cond-> touched-layouts
                    (:layout shape)
                    (conj (:id shape)))

                  modif-tree
                  (-> modif-tree
                      (set-children-modifiers shape objects ignore-constraints snap-pixel?))]

              (recur (first pending) (rest pending) modif-tree touched-layouts))

            [modif-tree touched-layouts]))

        ;; Second: Calculate layout positioning
        modif-tree
        (loop [current (first touched-layouts)
               pending (rest touched-layouts)
               modif-tree modif-tree]

          (if (some? current)
            (let [modif-tree (set-layout-modifiers modif-tree objects current)]
              (recur (first pending) (rest pending) modif-tree))
            modif-tree))]

    modif-tree))
