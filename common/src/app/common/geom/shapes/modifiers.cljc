;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.modifiers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.constraints :as gct]
   [app.common.geom.shapes.flex-layout :as gcl]
   [app.common.geom.shapes.pixel-precision :as gpp]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
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

(defn children-sequence
  "Given an id returns a sequence of its children"
  [id objects]

  (->> (tree-seq
        #(d/not-empty? (dm/get-in objects [% :shapes]))
        #(dm/get-in objects [% :shapes])
        id)
       (map #(get objects %))))

(defn resolve-tree-sequence
  "Given the ids that have changed search for layout roots to recalculate"
  [ids objects]

  (us/assert!
   :expr (or (nil? ids) (set? ids))
   :hint (dm/str "tree sequence from not set: " ids))

  (letfn [(get-tree-root ;; Finds the tree root for the current id
            [id]

            (loop [current id
                   result  id]
              (let [shape (get objects current)
                    parent (get objects (:parent-id shape))]
                (cond
                  (or (not shape) (= uuid/zero current))
                  result

                  ;; Frame found, but not layout we return the last layout found (or the id)
                  (and (= :frame (:type parent))
                       (not (ctl/layout? parent)))
                  result

                  ;; Layout found. We continue upward but we mark this layout
                  (ctl/layout? parent)
                  (recur (:id parent) (:id parent))

                  ;; If group or boolean or other type of group we continue with the last result
                  :else
                  (recur (:id parent) result)))))

          (calculate-common-roots ;; Given some roots retrieves the minimum number of tree roots
            [result id]
            (if (= id uuid/zero)
              result
              (let [root (get-tree-root id)

                    ;; Remove the children from the current root
                    result
                    (into #{} (remove #(cph/is-child? objects root %)) result)

                    contains-parent?
                    (some #(cph/is-child? objects % root) result)]

                (cond-> result
                  (not contains-parent?)
                  (conj root)))))]

    (let [roots (->> ids (reduce calculate-common-roots #{}))]
      (concat
       (when (contains? ids uuid/zero) [(get objects uuid/zero)])
       (mapcat #(children-sequence % objects) roots)))))

(defn- set-children-modifiers
  "Propagates the modifiers from a parent too its children applying constraints if necesary"
  [modif-tree objects bounds parent transformed-parent-bounds ignore-constraints]
  (let [children  (:shapes parent)
        modifiers (dm/get-in modif-tree [(:id parent) :modifiers])]

    ;; Move modifiers don't need to calculate constraints
    (if (ctm/only-move? modifiers)
      (loop [modif-tree modif-tree
             children (seq children)]
        (if-let [current (first children)]
          (recur (update-in modif-tree [current :modifiers] ctm/add-modifiers modifiers)
                 (rest children))
          modif-tree))

      ;; Check the constraints, then resize
      (let [parent-id (:id parent)
            parent-bounds (gtr/transform-bounds @(get bounds parent-id) (ctm/select-parent modifiers))]
        (loop [modif-tree modif-tree
               children (seq children)]
          (if (empty? children)
            modif-tree
            (let [child-id        (first children)
                  child           (get objects child-id)
                  child-bounds    @(get bounds child-id)
                  child-modifiers (gct/calc-child-modifiers parent child modifiers ignore-constraints child-bounds parent-bounds transformed-parent-bounds)]
              (recur (cond-> modif-tree
                       (not (ctm/empty? child-modifiers))
                       (update-in [child-id :modifiers] ctm/add-modifiers child-modifiers))
                     (rest children)))))))))

(defn get-group-bounds
  [objects bounds modif-tree shape]
  (let [shape-id (:id shape)
        modifiers (-> (dm/get-in modif-tree [shape-id :modifiers])
                      (ctm/select-geometry))

        children (cph/get-immediate-children objects shape-id)]

    (cond
      (and (cph/mask-shape? shape) (seq children))
      (get-group-bounds objects bounds modif-tree (-> children first))

      (cph/group-shape? shape)
      (let [;; Transform here to then calculate the bounds relative to the transform
            current-bounds
            (cond-> @(get bounds shape-id)
              (not (ctm/empty? modifiers))
              (gtr/transform-bounds modifiers))

            children-bounds
            (->> children
                 (mapv #(get-group-bounds objects bounds modif-tree %)))]
        (gpo/merge-parent-coords-bounds children-bounds current-bounds))

      :else
      (cond-> @(get bounds shape-id)
        (not (ctm/empty? modifiers))
        (gtr/transform-bounds modifiers)))))

(defn- set-layout-modifiers
  [modif-tree objects bounds parent transformed-parent-bounds]

  (letfn [(apply-modifiers [child]
            [(-> (get-group-bounds objects bounds modif-tree child)
                 (gpo/parent-coords-bounds @transformed-parent-bounds))
             child])

          (set-child-modifiers [[layout-line modif-tree] [child-bounds child]]
            (let [[modifiers layout-line]
                  (gcl/layout-child-modifiers parent transformed-parent-bounds child child-bounds layout-line)

                  modif-tree
                  (cond-> modif-tree
                    (d/not-empty? modifiers)
                    (update-in [(:id child) :modifiers] ctm/add-modifiers modifiers))]

              [layout-line modif-tree]))]

    (let [children     (->> (cph/get-immediate-children objects (:id parent))
                            (remove :hidden)
                            (map apply-modifiers))
          layout-data  (gcl/calc-layout-data parent children @transformed-parent-bounds)
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

(defn- calc-auto-modifiers
  "Calculates the modifiers to adjust the bounds for auto-width/auto-height shapes"
  [objects bounds parent]
  (let [parent-id     (:id parent)
        parent-bounds (get bounds parent-id)

        set-parent-auto-width
        (fn [modifiers auto-width]
          (let [origin        (gpo/origin @parent-bounds)
                scale-width   (/ auto-width (gpo/width-points @parent-bounds))]
            (-> modifiers
                (ctm/resize-parent (gpt/point scale-width 1) origin (:transform parent) (:transform-inverse parent)))))

        set-parent-auto-height
        (fn [modifiers auto-height]
          (let [origin        (gpo/origin @parent-bounds)
                scale-height (/ auto-height (gpo/height-points @parent-bounds))]
            (-> modifiers
                (ctm/resize-parent (gpt/point 1 scale-height) origin (:transform parent) (:transform-inverse parent)))))

        children (->> (cph/get-immediate-children objects parent-id)
                      (remove :hidden))

        content-bounds
        (when (and (d/not-empty? children) (or (ctl/auto-height? parent) (ctl/auto-width? parent)))
          (gcl/layout-content-bounds bounds parent children))

        auto-width (when content-bounds (gpo/width-points content-bounds))
        auto-height (when content-bounds (gpo/height-points content-bounds))]

    (cond-> (ctm/empty)
      (and (some? auto-width) (ctl/auto-width? parent))
      (set-parent-auto-width auto-width)

      (and (some? auto-height) (ctl/auto-height? parent))
      (set-parent-auto-height auto-height))))

(defn- propagate-modifiers-constraints
  "Propagate modifiers to its children"
  [objects bounds ignore-constraints modif-tree parent]
  (let [parent-id      (:id parent)
        root?          (= uuid/zero parent-id)
        modifiers      (-> (dm/get-in modif-tree [parent-id :modifiers])
                           (ctm/select-geometry))
        has-modifiers? (ctm/child-modifiers? modifiers)
        parent?        (or (cph/group-like-shape? parent) (cph/frame-shape? parent))
        transformed-parent-bounds (delay (gtr/transform-bounds @(get bounds parent-id) modifiers))]

    (cond-> modif-tree
      (and has-modifiers? parent? (not root?))
      (set-children-modifiers objects bounds parent transformed-parent-bounds ignore-constraints))))

(defn- propagate-modifiers-layout
  "Propagate modifiers to its children"
  [objects bounds ignore-constraints [modif-tree autolayouts] parent]
  (let [parent-id      (:id parent)
        root?          (= uuid/zero parent-id)
        modifiers      (-> (dm/get-in modif-tree [parent-id :modifiers])
                           (ctm/select-geometry))
        has-modifiers? (ctm/child-modifiers? modifiers)
        layout?        (ctl/layout? parent)
        auto?          (or (ctl/auto-height? parent) (ctl/auto-width? parent))
        parent?        (or (cph/group-like-shape? parent) (cph/frame-shape? parent))

        transformed-parent-bounds (delay (gtr/transform-bounds @(get bounds parent-id) modifiers))]

    [(cond-> modif-tree
       (and (not layout?) has-modifiers? parent? (not root?))
       (set-children-modifiers objects bounds parent transformed-parent-bounds ignore-constraints)

       layout?
       (set-layout-modifiers objects bounds parent transformed-parent-bounds))

     ;; Auto-width/height can change the positions in the parent so we need to recalculate
     (cond-> autolayouts auto? (conj (:id parent)))]))

(defn- apply-structure-modifiers
  [objects modif-tree]
  (letfn [(apply-shape [objects [id {:keys [modifiers]}]]
            (cond-> objects
              (ctm/has-structure? modifiers)
              (update id ctm/apply-structure-modifiers modifiers)))]
    (reduce apply-shape objects modif-tree)))

(defn merge-modif-tree
  [modif-tree other-tree]
  (reduce (fn [modif-tree [id {:keys [modifiers]}]]
            (update-in modif-tree [id :modifiers] ctm/add-modifiers modifiers))
          modif-tree
          other-tree))

(defn transform-bounds
  ([bounds objects modif-tree]
   (transform-bounds bounds objects modif-tree (->> (keys modif-tree) (map #(get objects %)))))
  ([bounds objects modif-tree tree-seq]

   (loop [result bounds
          shapes (reverse tree-seq)]
     (if (empty? shapes)
       result

       (let [shape (first shapes)
             new-bounds (delay (get-group-bounds objects bounds modif-tree shape))
             result (assoc result (:id shape) new-bounds)]
         (recur result (rest shapes)))))))

(defn reflow-layout
  [objects old-modif-tree bounds ignore-constraints id]

  (let [tree-seq (children-sequence id objects)

        [modif-tree _]
        (reduce
         #(propagate-modifiers-layout objects bounds ignore-constraints %1 %2) [{} #{}]
         tree-seq)

        bounds (transform-bounds bounds objects modif-tree tree-seq)

        modif-tree (merge-modif-tree old-modif-tree modif-tree)]
    [modif-tree bounds]))

(defn sizing-auto-modifiers
  "Recalculates the layouts to adjust the sizing: auto new sizes"
  [modif-tree sizing-auto-layouts objects bounds ignore-constraints]
  (let [;; Step-1 resize the auto-width/height. Reflow the parents if they are also auto-width/height
        [modif-tree bounds to-reflow]
        (loop [modif-tree modif-tree
               bounds bounds
               sizing-auto-layouts (reverse sizing-auto-layouts)
               to-reflow #{}]
          (if-let [current (first sizing-auto-layouts)]
            (let [parent-base (get objects current)

                  [modif-tree bounds]
                  (if (contains? to-reflow current)
                    (reflow-layout objects modif-tree bounds ignore-constraints current)
                    [modif-tree bounds])

                  auto-resize-modifiers
                  (calc-auto-modifiers objects bounds parent-base)

                  to-reflow
                  (cond-> to-reflow
                    (contains? to-reflow current)
                    (disj current))]

              (if (ctm/empty? auto-resize-modifiers)
                (recur modif-tree
                       bounds
                       (rest sizing-auto-layouts)
                       to-reflow)

                (let [resize-modif-tree {current {:modifiers auto-resize-modifiers}}

                      tree-seq (children-sequence current objects)

                      [resize-modif-tree _]
                      (reduce
                       #(propagate-modifiers-layout objects bounds ignore-constraints %1 %2) [resize-modif-tree #{}]
                       tree-seq)

                      bounds (transform-bounds bounds objects resize-modif-tree tree-seq)

                      modif-tree (merge-modif-tree modif-tree resize-modif-tree)

                      to-reflow
                      (cond-> to-reflow
                        (and (ctl/layout-descent? objects parent-base)
                             (not= uuid/zero (:frame-id parent-base)))
                        (conj (:frame-id parent-base)))]
                  (recur modif-tree
                         bounds
                         (rest sizing-auto-layouts)
                         to-reflow))))
            [modif-tree bounds to-reflow]))

        ;; Step-2: After resizing we still need to reflow the layout parents that are not auto-width/height

        tree-seq (resolve-tree-sequence to-reflow objects)

        [reflow-modif-tree _]
        (reduce
         #(propagate-modifiers-layout objects bounds ignore-constraints %1 %2) [{} #{}]
         tree-seq)

        result (merge-modif-tree modif-tree reflow-modif-tree)]

    result))

(defn set-objects-modifiers
  ([modif-tree objects]
   (set-objects-modifiers modif-tree objects nil))

  ([modif-tree objects params]
   (set-objects-modifiers nil modif-tree objects params))

  ([old-modif-tree modif-tree objects
    {:keys [ignore-constraints snap-pixel? snap-precision snap-ignore-axis]
     :or {ignore-constraints false snap-pixel? false snap-precision 1 snap-ignore-axis nil}}]
   (let [objects (-> objects
                     (cond-> (some? old-modif-tree)
                       (apply-structure-modifiers old-modif-tree))
                     (apply-structure-modifiers modif-tree))

         modif-tree
         (cond-> modif-tree
           snap-pixel? (gpp/adjust-pixel-precision objects snap-precision snap-ignore-axis))

         bounds (d/lazy-map (keys objects) #(dm/get-in objects [% :points]))
         bounds (cond-> bounds
                  (some? old-modif-tree)
                  (transform-bounds objects old-modif-tree))

         shapes-tree (resolve-tree-sequence (-> modif-tree keys set) objects)

         ;; Calculate the input transformation and constraints
         modif-tree (reduce #(propagate-modifiers-constraints objects bounds ignore-constraints %1 %2) modif-tree shapes-tree)
         bounds (transform-bounds bounds objects modif-tree shapes-tree)

         [modif-tree-layout sizing-auto-layouts]
         (reduce #(propagate-modifiers-layout objects bounds ignore-constraints %1 %2) [{} #{}] shapes-tree)

         modif-tree (merge-modif-tree modif-tree modif-tree-layout)

         ;; Calculate hug layouts positions
         bounds (transform-bounds bounds objects modif-tree-layout shapes-tree)

         modif-tree
         (-> modif-tree
             (sizing-auto-modifiers sizing-auto-layouts objects bounds ignore-constraints))

         modif-tree
         (if old-modif-tree
           (merge-modif-tree old-modif-tree modif-tree)
           modif-tree)]

     ;;#?(:cljs
     ;;   (.log js/console ">result" (modif->js modif-tree objects)))
     modif-tree)))
