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
                  (conj root)))))

          (generate-tree ;; Generate a tree sequence from a given root id
            [id]
            (->> (tree-seq
                  #(d/not-empty? (dm/get-in objects [% :shapes]))
                  #(dm/get-in objects [% :shapes])
                  id)
                 (map #(get objects %))))]

    (let [roots (->> ids (reduce calculate-common-roots #{}))]
      (concat
       (when (contains? ids uuid/zero) [(get objects uuid/zero)])
       (mapcat generate-tree roots)))))

(defn- set-children-modifiers
  "Propagates the modifiers from a parent too its children applying constraints if necesary"
  [modif-tree objects parent transformed-parent ignore-constraints]
  (let [children  (:shapes parent)
        modifiers (dm/get-in modif-tree [(:id parent) :modifiers])]

    (if (ctm/only-move? modifiers)
      ;; Move modifiers don't need to calculate constraints
      (loop [modif-tree modif-tree
             children (seq children)]
        (if-let [current (first children)]
          (recur (update-in modif-tree [current :modifiers] ctm/add-modifiers modifiers)
                 (rest children))
          modif-tree))

      ;; Check the constraints, then resize
      (let [parent (gtr/transform-shape parent (ctm/select-parent-modifiers modifiers))]
        (loop [modif-tree modif-tree
               children (seq children)]
          (if-let [current (first children)]
            (let [child-modifiers (gct/calc-child-modifiers parent (get objects current) modifiers ignore-constraints transformed-parent)]
              (recur (cond-> modif-tree
                       (not (ctm/empty? child-modifiers))
                       (update-in [current :modifiers] ctm/add-modifiers child-modifiers))
                     (rest children)))
            modif-tree))))))

(defn- process-layout-children
  [modif-tree objects parent transformed-parent]
  (letfn [(process-child [modif-tree child]
            (let [modifiers (dm/get-in modif-tree [(:id parent) :modifiers])
                  child-modifiers (-> modifiers
                                      (ctm/select-child-geometry-modifiers)
                                      (gcl/normalize-child-modifiers parent child transformed-parent))]
              (cond-> modif-tree
                (not (ctm/empty? child-modifiers))
                (update-in [(:id child) :modifiers] ctm/add-modifiers child-modifiers))))]
    (let [children (map (d/getf objects) (:shapes transformed-parent))]
      (reduce process-child modif-tree children))))

(defn- set-layout-modifiers
  [modif-tree objects parent]

  (letfn [(apply-modifiers [modif-tree child]
            (let [modifiers (-> (dm/get-in modif-tree [(:id child) :modifiers])
                                (ctm/select-geometry))]
              (cond
                (cph/group-like-shape? child)
                (gtr/apply-group-modifiers child objects modif-tree)

                (some? modifiers)
                (gtr/transform-shape child modifiers)

                :else
                child)))

          (set-child-modifiers [parent [layout-line modif-tree] child]
            (let [[modifiers layout-line]
                  (gcl/layout-child-modifiers parent child layout-line)

                  modif-tree
                  (cond-> modif-tree
                    (d/not-empty? modifiers)
                    (update-in [(:id child) :modifiers] ctm/add-modifiers modifiers))]

              [layout-line modif-tree]))]

    (let [children     (map (d/getf objects) (:shapes parent))
          children     (->> children (map (partial apply-modifiers modif-tree)))
          layout-data  (gcl/calc-layout-data parent children)
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
                (reduce (partial set-child-modifiers parent) [layout-line modif-tree] children)]
            (recur modif-tree (first pending) (rest pending) to-idx))

          modif-tree)))))

(defn- calc-auto-modifiers
  "Calculates the modifiers to adjust the bounds for auto-width/auto-height shapes"
  [objects parent]
  (letfn [(set-parent-auto-width
            [modifiers auto-width]
            (let [origin (-> parent :points first)
                  scale-width (/ auto-width (-> parent :selrect :width) )]
              (-> modifiers
                  (ctm/resize-parent (gpt/point scale-width 1) origin (:transform parent) (:transform-inverse parent)))))

          (set-parent-auto-height
            [modifiers auto-height]
            (let [origin (-> parent :points first)
                  scale-height (/ auto-height (-> parent :selrect :height) )]
              (-> modifiers
                  (ctm/resize-parent (gpt/point 1 scale-height) origin (:transform parent) (:transform-inverse parent)))))]

    (let [children (->> parent :shapes (map (d/getf objects)))

          {auto-width :width auto-height :height}
          (when (and (d/not-empty? children) (or (ctl/auto-height? parent) (ctl/auto-width? parent)))
            (gcl/layout-content-bounds parent children))]

      (cond-> (ctm/empty)
        (and (some? auto-width) (ctl/auto-width? parent))
        (set-parent-auto-width auto-width)

        (and (some? auto-height) (ctl/auto-height? parent))
        (set-parent-auto-height auto-height)))))

(defn- propagate-modifiers
  "Propagate modifiers to its children"
  [objects ignore-constraints [modif-tree autolayouts] parent]
  (let [parent-id (:id parent)
        root? (= uuid/zero parent-id)
        modifiers (-> (dm/get-in modif-tree [parent-id :modifiers])
                      (ctm/select-geometry))
        transformed-parent (gtr/transform-shape parent modifiers)

        has-modifiers? (ctm/child-modifiers? modifiers)
        layout? (ctl/layout? parent)
        auto?   (or (ctl/auto-height? transformed-parent) (ctl/auto-width? transformed-parent))
        parent? (or (cph/group-like-shape? parent) (cph/frame-shape? parent))

        ;; If the current child is inside the layout we ignore the constraints
        inside-layout? (ctl/inside-layout? objects parent)]

    [(cond-> modif-tree
       (and (not layout?) has-modifiers? parent? (not root?))
       (set-children-modifiers objects parent transformed-parent (or ignore-constraints inside-layout?))

       layout?
       (-> (process-layout-children objects parent transformed-parent)
           (set-layout-modifiers objects transformed-parent)))

     ;; Auto-width/height can change the positions in the parent so we need to recalculate
     (cond-> autolayouts auto? (conj (:id parent)))]))

(defn- apply-structure-modifiers
  [objects modif-tree]
  (letfn [(apply-shape [objects [id {:keys [modifiers]}]]
            (cond-> objects
              (ctm/has-structure? modifiers)
              (update id ctm/apply-structure-modifiers modifiers)))]
    (reduce apply-shape objects modif-tree)))

(defn- apply-partial-objects-modifiers
  [objects tree-seq modif-tree]

  (letfn [(apply-shape [objects {:keys [id] :as shape}]
            (if (cph/group-shape? shape)
              (let [children (cph/get-children objects id)]
                (assoc objects id
                       (cond
                         (cph/mask-shape? shape)
                         (gtr/update-mask-selrect shape children)

                         :else
                         (gtr/update-group-selrect shape children))))

              (let [modifiers (get-in modif-tree [id :modifiers])
                    object (cond-> shape
                             (some? modifiers)
                             (gtr/transform-shape modifiers))]
                (assoc objects id object))))]

    (reduce apply-shape objects (reverse tree-seq))))

(defn merge-modif-tree
  [modif-tree other-tree]
  (reduce (fn [modif-tree [id {:keys [modifiers]}]]
            (update-in modif-tree [id :modifiers] ctm/add-modifiers modifiers))
          modif-tree
          other-tree))

(defn sizing-auto-modifiers
  "Recalculates the layouts to adjust the sizing: auto new sizes"
  [modif-tree sizing-auto-layouts objects ignore-constraints]
  (loop [modif-tree modif-tree
         sizing-auto-layouts (reverse sizing-auto-layouts)]
    (if-let [current (first sizing-auto-layouts)]
      (let [parent-base (get objects current)
            tree-seq (resolve-tree-sequence #{current} objects)

            ;; Apply the current stack of transformations so we can calculate the auto-layouts
            objects (apply-partial-objects-modifiers objects tree-seq modif-tree)

            resize-modif-tree
            {current {:modifiers (calc-auto-modifiers objects parent-base)}}

            tree-seq (resolve-tree-sequence #{current} objects)

            [resize-modif-tree _]
            (reduce (partial propagate-modifiers objects ignore-constraints) [resize-modif-tree #{}] tree-seq)

            modif-tree (merge-modif-tree modif-tree resize-modif-tree)]
        (recur modif-tree (rest sizing-auto-layouts)))
      modif-tree)))

(defn set-objects-modifiers
  [modif-tree objects ignore-constraints snap-pixel?]

  (let [objects (apply-structure-modifiers objects modif-tree)
        shapes-tree (resolve-tree-sequence (-> modif-tree keys set) objects)

        [modif-tree sizing-auto-layouts]
        (reduce (partial propagate-modifiers objects ignore-constraints) [modif-tree #{}] shapes-tree)

        ;; Calculate hug layouts positions
        modif-tree (sizing-auto-modifiers modif-tree sizing-auto-layouts objects ignore-constraints)

        modif-tree
        (cond-> modif-tree
          snap-pixel? (gpp/adjust-pixel-precision objects))]

    ;;#?(:cljs
    ;;   (.log js/console ">result" (modif->js modif-tree objects)))
    modif-tree))
