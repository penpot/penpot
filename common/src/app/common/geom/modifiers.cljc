;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.modifiers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.bounds-map :as cgb]
   [app.common.geom.modif-tree :as cgt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.constraints :as gct]
   [app.common.geom.shapes.flex-layout :as gcfl]
   [app.common.geom.shapes.grid-layout :as gcgl]
   [app.common.geom.shapes.min-size-layout]
   [app.common.geom.shapes.pixel-precision :as gpp]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.geom.shapes.tree-seq :as cgst]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

;;#?(:cljs
;;   (defn modif->js
;;     [modif-tree objects]
;;     (clj->js (into {}
;;                    (map (fn [[k v]]
;;                           [(get-in objects [k :name]) v]))
;;                    modif-tree))))

(defn- set-children-modifiers
  "Propagates the modifiers from a parent too its children applying constraints if necesary"
  [modif-tree children objects bounds parent transformed-parent-bounds ignore-constraints]
  (let [modifiers (dm/get-in modif-tree [(:id parent) :modifiers])]
    ;; Move modifiers don't need to calculate constraints
    (cond
      (ctm/empty? modifiers)
      modif-tree

      (ctm/only-move? modifiers)
      (reduce #(cgt/add-modifiers %1 %2 modifiers) modif-tree children)

      ;; Check the constraints, then resize
      :else
      (let [parent-id (:id parent)
            parent-bounds (gtr/transform-bounds @(get bounds parent-id) (ctm/select-parent modifiers))]
        (->> children
             (reduce
              (fn [modif-tree child-id]
                (if-let [child (get objects child-id)]
                  (let [child-bounds @(get bounds child-id)
                        child-modifiers
                        (gct/calc-child-modifiers
                         parent child modifiers ignore-constraints
                         child-bounds
                         parent-bounds transformed-parent-bounds)]

                    (cgt/add-modifiers modif-tree child-id child-modifiers))
                  modif-tree))
              modif-tree))))))

(defn- set-flex-layout-modifiers
  [modif-tree children objects bounds parent transformed-parent-bounds]

  (letfn [(apply-modifiers [bounds child]
            [(-> @(get bounds (:id child))
                 (gpo/parent-coords-bounds @transformed-parent-bounds))
             child])

          (set-child-modifiers [[layout-line modif-tree] [child-bounds child]]
            (let [[modifiers layout-line]
                  (gcfl/layout-child-modifiers parent transformed-parent-bounds child child-bounds layout-line)]
              [layout-line (cgt/add-modifiers modif-tree (:id child) modifiers)]))]

    (let [bounds (cgb/transform-bounds-map bounds objects modif-tree children)

          children
          (->> children
               (keep (d/getf objects))
               (remove gco/invalid-geometry?)
               (map (partial apply-modifiers bounds)))

          layout-data  (gcfl/calc-layout-data parent @transformed-parent-bounds children bounds objects)
          children     (into [] (cond-> children (not (:reverse? layout-data)) reverse))
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
                (reduce set-child-modifiers [layout-line modif-tree] children)]
            (recur modif-tree (first pending) (rest pending) to-idx))

          modif-tree)))))

(defn- set-grid-layout-modifiers
  [modif-tree objects bounds parent transformed-parent-bounds]

  (letfn [(apply-modifiers [bounds child]
            [(-> @(get bounds (:id child))
                 (gpo/parent-coords-bounds @transformed-parent-bounds))
             child])

          (set-child-modifiers [modif-tree grid-data cell-data [child-bounds child]]
            (let [modifiers
                  (gcgl/child-modifiers parent transformed-parent-bounds child child-bounds grid-data cell-data)]
              (cgt/add-modifiers modif-tree (:id child) modifiers)))]

    (let [bounds (cgb/transform-bounds-map bounds objects modif-tree (:shapes parent))

          children
          (->> (cfh/get-immediate-children objects (:id parent) {:remove-hidden true})
               (map (partial apply-modifiers bounds)))
          grid-data    (gcgl/calc-layout-data parent @transformed-parent-bounds children bounds objects)]
      (loop [modif-tree modif-tree
             bound+child (first children)
             pending (rest children)]
        (if (some? bound+child)
          (let [cell-data (gcgl/get-cell-data grid-data @transformed-parent-bounds bound+child)
                modif-tree (cond-> modif-tree
                             (some? cell-data)
                             (set-child-modifiers grid-data cell-data bound+child))]
            (recur modif-tree (first pending) (rest pending)))
          modif-tree)))))

(defn- set-modifiers-constraints
  "Propagate modifiers to its children"
  [objects bounds ignore-constraints modif-tree parent]
  (let [parent-id      (:id parent)
        children       (:shapes parent)
        root?          (= uuid/zero parent-id)
        modifiers      (-> (dm/get-in modif-tree [parent-id :modifiers])
                           (ctm/select-geometry))
        has-modifiers? (ctm/child-modifiers? modifiers)
        parent?        (or (cfh/group-like-shape? parent) (cfh/frame-shape? parent))
        transformed-parent-bounds (delay (gtr/transform-bounds @(get bounds parent-id) modifiers))]

    (cond-> modif-tree
      (and has-modifiers? parent? (not root?))
      (set-children-modifiers children objects bounds parent transformed-parent-bounds ignore-constraints))))

(defn- set-modifiers-layout
  "Propagate modifiers to its children"
  ([objects bounds ignore-constraints parent]
   (set-modifiers-layout objects bounds ignore-constraints {} parent))
  ([objects bounds ignore-constraints modif-tree parent]
   (let [parent-id       (:id parent)
         root?           (= uuid/zero parent-id)
         modifiers       (-> (dm/get-in modif-tree [parent-id :modifiers])
                             (ctm/select-geometry))
         has-modifiers?  (ctm/child-modifiers? modifiers)
         flex-layout?    (ctl/flex-layout? parent)
         grid-layout?    (ctl/grid-layout? parent)
         parent?         (or (cfh/group-like-shape? parent) (cfh/frame-shape? parent))

         transformed-parent-bounds (delay (gtr/transform-bounds @(get bounds parent-id) modifiers))

         children-modifiers
         (if (or flex-layout? grid-layout?)
           (->> (:shapes parent)
                (filter #(ctl/position-absolute? objects %)))
           (:shapes parent))

         children-layout
         (when (or flex-layout? grid-layout?)
           (->> (:shapes parent)
                (remove #(ctl/position-absolute? objects %))))]

     (cond-> modif-tree
       (and has-modifiers? parent? (not root?))
       (set-children-modifiers children-modifiers objects bounds parent transformed-parent-bounds ignore-constraints)

       flex-layout?
       (set-flex-layout-modifiers children-layout objects bounds parent transformed-parent-bounds)

       grid-layout?
       (set-grid-layout-modifiers objects bounds parent transformed-parent-bounds)))))

(defn propagate-modifiers-constraints
  ([objects bounds ignore-constraints shapes]
   (propagate-modifiers-constraints objects bounds ignore-constraints {} shapes))
  ([objects bounds ignore-constraints modif-tree shapes]
   (reduce #(set-modifiers-constraints objects bounds ignore-constraints %1 %2) modif-tree shapes)))

(defn propagate-modifiers-layouts
  ([objects bounds ignore-constraints shapes]
   (propagate-modifiers-layouts objects bounds ignore-constraints {} shapes))
  ([objects bounds ignore-constraints modif-tree shapes]
   (reduce #(set-modifiers-layout objects bounds ignore-constraints %1 %2) modif-tree shapes)))

(defn- calc-auto-modifiers
  "Calculates the modifiers to adjust the bounds for auto-width/auto-height shapes"
  [objects bounds parent]
  (let [parent-id     (:id parent)
        parent-bounds (get bounds parent-id)

        set-parent-auto-width
        (fn [modifiers auto-width]
          (let [origin        (gpo/origin @parent-bounds)
                current-width  (gpo/width-points @parent-bounds)
                scale-width   (/ auto-width current-width)]
            (-> modifiers
                (ctm/resize (gpt/point scale-width 1) origin (:transform parent) (:transform-inverse parent)))))

        set-parent-auto-height
        (fn [modifiers auto-height]
          (let [origin        (gpo/origin @parent-bounds)
                current-height (gpo/height-points @parent-bounds)
                scale-height (/ auto-height current-height)]
            (-> modifiers
                (ctm/resize (gpt/point 1 scale-height) origin (:transform parent) (:transform-inverse parent)))))

        children (->> (cfh/get-immediate-children objects parent-id)
                      (remove ctl/position-absolute?)
                      (remove gco/invalid-geometry?))

        auto?        (or (ctl/auto? parent)
                         (and (ctl/grid-layout? objects (:parent-id parent))
                              (ctl/fill? parent)))
        auto-width?  (or (ctl/auto-width? parent)
                         (and (ctl/grid-layout? objects (:parent-id parent))
                              (ctl/fill-width? parent)))
        auto-height? (or (ctl/auto-height? parent)
                         (and (ctl/grid-layout? objects (:parent-id parent))
                              (ctl/fill-height? parent)))

        content-bounds
        (when (and (d/not-empty? children) auto?)
          (cond
            (ctl/flex-layout? parent)
            (gcfl/layout-content-bounds bounds parent children objects)

            (ctl/grid-layout? parent)
            (let [children (->>  children
                                 (map (fn [child] [@(get bounds (:id child)) child])))
                  layout-data (gcgl/calc-layout-data parent @parent-bounds children bounds objects)]
              (gcgl/layout-content-bounds bounds parent layout-data))))

        auto-width (when content-bounds (gpo/width-points content-bounds))
        auto-height (when content-bounds (gpo/height-points content-bounds))]
    (cond-> (ctm/empty)
      (and (some? auto-width) auto-width?)
      (set-parent-auto-width auto-width)

      (and (some? auto-height) auto-height?)
      (set-parent-auto-height auto-height))))

(defn find-auto-layouts
  [objects shapes]

  (letfn [(mk-check-auto-layout [objects]
            (fn [shape]
              ;; Auto-width/height can change the positions in the parent so we need to recalculate
              ;; also if the child is fill width/height inside a grid layout
              (when (or (ctl/auto? shape)
                        (and (ctl/grid-layout? objects (:parent-id shape)) (ctl/fill? shape)))
                (:id shape))))]
    (into (d/ordered-set)
          (keep (mk-check-auto-layout objects))
          shapes)))

(defn full-tree?
  "Checks if we need to calculate the full tree or we can calculate just a partial tree. Partial
  trees are more efficient but cannot be done when the layout is centered."
  [objects layout-id]
  (let [layout-justify-content (get-in objects [layout-id :layout-justify-content])]
    (contains? #{:center :end :space-around :space-evenly :stretch} layout-justify-content)))

(defn sizing-auto-modifiers
  "Recalculates the layouts to adjust the sizing: auto new sizes"
  [modif-tree sizing-auto-layouts objects bounds ignore-constraints]

  (let [calculate-modifiers
        (fn [[modif-tree bounds] layout-id]
          (let [layout (get objects layout-id)
                auto-modifiers (calc-auto-modifiers objects bounds layout)]

            (if (and (ctm/empty? auto-modifiers) (not (ctl/grid-layout? layout)))
              [modif-tree bounds]

              (let [from-layout
                    (->> (cfh/get-parent-ids objects layout-id)
                         (d/seek sizing-auto-layouts))

                    shapes
                    (if (and from-layout (not (full-tree? objects from-layout)))
                      (cgst/resolve-subtree from-layout layout-id objects)
                      (cgst/resolve-tree #{layout-id} objects))

                    auto-modif-tree {layout-id {:modifiers auto-modifiers}}
                    auto-modif-tree (propagate-modifiers-layouts objects bounds ignore-constraints auto-modif-tree shapes)

                    bounds (cgb/transform-bounds-map bounds objects auto-modif-tree)
                    modif-tree (cgt/merge-modif-tree modif-tree auto-modif-tree)]
                [modif-tree bounds]))))]
    (->> sizing-auto-layouts
         (reverse)
         (reduce calculate-modifiers [modif-tree bounds])
         (first))))

(defn filter-layouts-ids
  "Returns a list of ids without the root-frames with only move"
  [objects modif-tree]
  (->> modif-tree
       (remove (fn [[id {:keys [modifiers]}]]
                 (or (ctm/empty? modifiers)
                     (and (cfh/root-frame? objects id)
                          (ctm/only-move? modifiers)))))
       (map first)
       (set)))

(defn set-objects-modifiers
  "Applies recursively the modifiers and calculate the layouts and constraints for all the items to be placed correctly"
  ([modif-tree objects]
   (set-objects-modifiers modif-tree objects nil))

  ([modif-tree objects params]
   (set-objects-modifiers nil modif-tree objects params))

  ([old-modif-tree modif-tree objects
    {:keys [ignore-constraints snap-pixel? snap-precision snap-ignore-axis]
     :or {ignore-constraints false
          snap-pixel? false
          snap-precision 1
          snap-ignore-axis nil}}]

   (let [;; Apply structure modifiers. Things that are not related to geometry
         objects
         (-> objects
             (cond-> (some? old-modif-tree)
               (cgt/apply-structure-modifiers old-modif-tree))
             (cgt/apply-structure-modifiers modif-tree))

         ;; Creates the sequence of shapes with the shapes that are modified
         shapes-tree-all
         (cgst/resolve-tree (-> modif-tree keys set) objects)

         ;; This second sequence is used to recalculate layouts (we remove moved root-frames)
         shapes-tree-layout
         (cgst/resolve-tree (filter-layouts-ids objects modif-tree) objects)

         bounds-map
         (cond-> (cgb/objects->bounds-map objects)
           (some? old-modif-tree)
           (cgb/transform-bounds-map objects old-modif-tree))

         ;; Round the transforms if the snap-to-pixel is active
         modif-tree
         (cond-> modif-tree
           snap-pixel?
           (gpp/adjust-pixel-precision objects snap-precision snap-ignore-axis))

         ;; Propagates the modifiers to the normal shapes with constraints
         modif-tree
         (propagate-modifiers-constraints objects bounds-map ignore-constraints modif-tree shapes-tree-all)

         bounds-map
         (cgb/transform-bounds-map bounds-map objects modif-tree)

         modif-tree-layout
         (propagate-modifiers-layouts objects bounds-map ignore-constraints shapes-tree-layout)

         modif-tree
         (cgt/merge-modif-tree modif-tree modif-tree-layout)

         ;; Calculate hug layouts positions
         bounds-map
         (cgb/transform-bounds-map bounds-map objects modif-tree-layout)

         ;; Find layouts with auto width/height
         sizing-auto-layouts (find-auto-layouts objects shapes-tree-layout)

         modif-tree
         (sizing-auto-modifiers modif-tree sizing-auto-layouts objects bounds-map ignore-constraints)

         modif-tree
         (if old-modif-tree
           (cgt/merge-modif-tree old-modif-tree modif-tree)
           modif-tree)]

     ;;#?(:cljs
     ;;   (.log js/console ">result" (modif->js modif-tree objects)))
     modif-tree)))
