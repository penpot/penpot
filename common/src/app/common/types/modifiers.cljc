;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.modifiers
  (:refer-clojure :exclude [empty empty?])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.corners :as gsc]
   [app.common.geom.shapes.effects :as gse]
   [app.common.geom.shapes.strokes :as gss]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [clojure.core :as c]))

;; --- Modifiers

;; Moodifiers types
;;  - geometry-parent: Geometry non-recursive
;;     * move
;;     * resize
;;     * rotation
;;  - geometry-child: Geometry recursive
;;     * move
;;     * resize
;;     * rotation
;;  - structure-parent: Structure non recursive
;;     * add-children
;;     * remove-children
;;     * reflow
;;  - structure-child: Structure recursive
;;     * scale-content
;;     * rotation
;;     * change-properties

(defrecord Modifiers
           [last-order ;; Last `order` attribute in the geometry list
            geometry-parent
            geometry-child
            structure-parent
            structure-child])

(defrecord GeometricOperation
           [order ;; Need the order to keep consistent between geometry-parent and geometry-child
            type
            vector
            origin
            transform
            transform-inverse
            rotation
            center])

(defrecord StructureOperation
           [type
            property
            value
            index])

;; Record constructors

(defn- move-op
  [order vector]
  (GeometricOperation. order :move vector nil nil nil nil nil))

(defn- resize-op
  ([order vector origin]
   (GeometricOperation. order :resize vector origin nil nil nil nil))

  ([order vector origin transform transform-inverse]
   (GeometricOperation. order :resize vector origin transform transform-inverse nil nil)))

(defn- rotation-geom-op
  [order center angle]
  (GeometricOperation. order :rotation nil nil nil nil angle center))

(defn- rotation-struct-op
  [angle]
  (StructureOperation. :rotation nil angle nil))

(defn- remove-children-op
  [shapes]
  (StructureOperation. :remove-children nil shapes nil))

(defn- add-children-op
  [shapes index]
  (StructureOperation. :add-children nil shapes index))

(defn- reflow-op
  []
  (StructureOperation. :reflow nil nil nil))

(defn- scale-content-op
  [value]
  (StructureOperation. :scale-content nil value nil))

(defn- change-property-op
  [property value]
  (StructureOperation. :change-property property value nil))

;; Private aux functions

(defn- move-vec?
  [vector]
  (or (not ^boolean (mth/almost-zero? (dm/get-prop vector :x)))
      (not ^boolean (mth/almost-zero? (dm/get-prop vector :y)))))

(defn- resize-vec?
  [vector]
  (or (not ^boolean (mth/almost-zero? (- (dm/get-prop vector :x) 1)))
      (not ^boolean (mth/almost-zero? (- (dm/get-prop vector :y) 1)))))

(defn- mergeable-move?
  [op1 op2]
  (let [type-op1 (dm/get-prop op1 :type)
        type-op2 (dm/get-prop op2 :type)]
    (and (= :move type-op1) (= :move type-op2))))

(defn- mergeable-resize?
  [op1 op2]
  (let [type-op1          (dm/get-prop op1 :type)
        transform-op1     (d/nilv (dm/get-prop op1 :transform) gmt/base)
        transform-inv-op1 (d/nilv (dm/get-prop op1 :transform-inverse) gmt/base)
        origin-op1        (dm/get-prop op1 :origin)

        type-op2          (dm/get-prop op2 :type)
        transform-op2     (d/nilv (dm/get-prop op2 :transform) gmt/base)
        transform-inv-op2 (d/nilv (dm/get-prop op2 :transform-inverse) gmt/base)
        origin-op2        (dm/get-prop op2 :origin)]

    (and (= :resize type-op1)
         (= :resize type-op2)

         ;; Same origin
         ^boolean (gpt/close? origin-op1 origin-op2)

         ;; Same transforms
         ^boolean (gmt/close? transform-op1 transform-op2)
         ^boolean (gmt/close? transform-inv-op1 transform-inv-op2))))

(defn- merge-move
  [op1 op2]
  (let [vector-op1 (dm/get-prop op1 :vector)
        vector-op2 (dm/get-prop op2 :vector)
        vector     (gpt/add vector-op1 vector-op2)]
    (assoc op1 :vector vector)))

(defn- merge-resize
  [op1 op2]
  (let [op1-vector (dm/get-prop op1 :vector)
        op1-x      (dm/get-prop op1-vector :x)
        op1-y      (dm/get-prop op1-vector :y)

        op2-vector (dm/get-prop op2 :vector)
        op2-x      (dm/get-prop op2-vector :x)
        op2-y      (dm/get-prop op2-vector :y)

        vector     (gpt/point (* op1-x op2-x)
                              (* op1-y op2-y))]
    (assoc op1 :vector vector)))

(defn- maybe-add-move
  "Check the last operation to check if we can stack it over the last one"
  [operations op]
  (if (c/empty? operations)
    [op]
    (let [head (peek operations)]
      (if (mergeable-move? head op)
        (let [item (merge-move head op)]
          (cond-> (pop operations)
            (move-vec? (dm/get-prop item :vector))
            (conj item)))
        (conj operations op)))))

(defn- maybe-add-resize
  "Check the last operation to check if we can stack it over the last one"
  ([operations op]
   (maybe-add-resize operations op nil))

  ([operations op {:keys [precise?]}]
   (if (c/empty? operations)
     [op]
     (let [head (peek operations)]
       (if (mergeable-resize? head op)
         (let [item (merge-resize head op)]
           (cond-> (pop operations)
             (or precise? (resize-vec? (dm/get-prop item :vector)))
             (conj item)))
         (conj operations op))))))

(defn valid-vector?
  [vector]
  (let [x (dm/get-prop vector :x)
        y (dm/get-prop vector :y)]
    (d/num? x y)))

;; Public builder API

(defn empty []
  (Modifiers. 0 [] [] [] []))

(defn move-parent
  ([modifiers x y]
   (move-parent modifiers (gpt/point x y)))

  ([modifiers vector]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (let [modifiers (or modifiers (empty))
         order     (inc (dm/get-prop modifiers :last-order))
         modifiers (assoc modifiers :last-order order)]
     (cond-> modifiers
       (move-vec? vector)
       (update :geometry-parent maybe-add-move (move-op order vector))))))

(defn resize-parent
  ([modifiers vector origin]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (let [modifiers (or modifiers (empty))
         order     (inc (dm/get-prop modifiers :last-order))
         modifiers (assoc modifiers :last-order order)]
     (cond-> modifiers
       (resize-vec? vector)
       (update :geometry-parent maybe-add-resize (resize-op order vector origin)))))

  ([modifiers vector origin transform transform-inverse]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (let [modifiers (or modifiers (empty))
         order     (inc (dm/get-prop modifiers :last-order))
         modifiers (assoc modifiers :last-order order)]
     (cond-> modifiers
       (resize-vec? vector)
       (update :geometry-parent maybe-add-resize (resize-op order vector origin transform transform-inverse))))))

(defn move
  ([modifiers x y]
   (move modifiers (gpt/point x y)))

  ([modifiers vector]
   (dm/assert!
    ["Invalid move vector: %1,%2" (:x vector) (:y vector)]
    (valid-vector? vector))

   (let [modifiers (or ^boolean modifiers (empty))
         order     (inc (dm/get-prop modifiers :last-order))
         modifiers (assoc modifiers :last-order order)]
     (cond-> modifiers
       (move-vec? vector)
       (update :geometry-child maybe-add-move (move-op order vector))))))

(defn resize
  ([modifiers vector origin]
   (assert (valid-vector? vector) (dm/str "Invalid resize vector: " (:x vector) "," (:y vector)))
   (let [modifiers (or ^boolean modifiers (empty))
         order     (inc (dm/get-prop modifiers :last-order))
         modifiers (assoc modifiers :last-order order)]
     (cond-> modifiers
       (resize-vec? vector)
       (update :geometry-child maybe-add-resize (resize-op order vector origin)))))

  ([modifiers vector origin transform transform-inverse]
   (resize modifiers vector origin transform transform-inverse nil))

  ;; `precise?` works so we don't remove almost empty resizes. This will be used in the pixel-precision
  ([modifiers vector origin transform transform-inverse {:keys [precise?]}]
   (assert (valid-vector? vector) (dm/str "Invalid resize vector: " (:x vector) "," (:y vector)))
   (let [modifiers (or modifiers (empty))
         order     (inc (dm/get-prop modifiers :last-order))
         modifiers (assoc modifiers :last-order order)]
     (cond-> modifiers
       (or precise? (resize-vec? vector))
       (update :geometry-child maybe-add-resize (resize-op order vector origin transform transform-inverse))))))

(defn rotation
  [modifiers center angle]
  (let [modifiers (or modifiers (empty))
        order     (inc (dm/get-prop modifiers :last-order))
        modifiers (assoc modifiers :last-order order)]
    (cond-> modifiers
      (not (mth/close? angle 0))
      (-> (update :structure-child conj (rotation-struct-op angle))
          (update :geometry-child conj (rotation-geom-op order center angle))))))

(defn remove-children
  [modifiers shapes]
  (cond-> (or modifiers (empty))
    (d/not-empty? shapes)
    (update :structure-parent conj (remove-children-op shapes))))

(defn add-children
  [modifiers shapes index]
  (cond-> (or modifiers (empty))
    (d/not-empty? shapes)
    (update :structure-parent conj (add-children-op shapes index))))

(defn reflow
  [modifiers]
  (-> (or modifiers (empty))
      (update :structure-parent conj (reflow-op))))

(defn scale-content
  [modifiers value]
  (-> (or modifiers (empty))
      (update :structure-child conj (scale-content-op value))))

(defn change-recursive-property
  [modifiers property value]
  (-> (or modifiers (empty))
      (update :structure-child conj (change-property-op property value))))

(defn change-property
  [modifiers property value]
  (-> (or modifiers (empty))
      (update :structure-parent conj (change-property-op property value))))

(defn- concat-geometry
  [operations other merge?]

  (cond
    (c/empty? operations)
    other

    (c/empty? other)
    operations

    :else
    (loop [result operations
           operations (seq other)]
      (if (c/empty? operations)
        result
        (let [current (first operations)
              result
              (cond
                (and merge? (= :move (dm/get-prop current :type)))
                (maybe-add-move result current)

                (and merge? (= :resize (dm/get-prop current :type)))
                (maybe-add-resize result current)

                :else
                (conj result current))]

          (recur result (rest operations)))))))

(defn increase-order
  [operations last-order]
  (->> operations
       (mapv #(update % :order + last-order))))

(defn add-modifiers
  [modifiers new-modifiers]

  (let [modifiers (or modifiers (empty))
        new-modifiers (or new-modifiers (empty))
        last-order (dm/get-prop modifiers :last-order)
        new-last-order (dm/get-prop new-modifiers :last-order)


        old-geom-child (dm/get-prop modifiers :geometry-child)
        new-geom-child (-> (dm/get-prop new-modifiers :geometry-child)
                           (increase-order last-order))

        old-geom-parent (dm/get-prop modifiers :geometry-parent)
        new-geom-parent (-> (dm/get-prop new-modifiers :geometry-parent)
                            (increase-order last-order))

        ;; We can only merge if the result will respect the global order in modifiers
        merge-child? (and (c/empty? new-geom-parent) (c/empty? old-geom-parent))
        merge-parent? (and (c/empty? new-geom-child) (c/empty? old-geom-child))]
    (-> modifiers
        (assoc  :last-order       (+ last-order new-last-order))
        (update :geometry-child   #(concat-geometry % new-geom-child merge-child?))
        (update :geometry-parent  #(concat-geometry % new-geom-parent merge-parent?))
        (update :structure-parent #(d/concat-vec [] % (dm/get-prop new-modifiers :structure-parent)))
        (update :structure-child  #(d/concat-vec [] % (dm/get-prop new-modifiers :structure-child))))))


;; These are convenience methods to create single operation modifiers without the builder

(defn move-modifiers
  ([x y]
   (move (empty) (gpt/point x y)))

  ([vector]
   (move (empty) vector)))

(defn move-parent-modifiers
  ([x y]
   (move-parent (empty) (gpt/point x y)))

  ([vector]
   (move-parent (empty) vector)))

(defn resize-modifiers
  ([vector origin]
   (resize (empty) vector origin))

  ([vector origin transform transform-inverse]
   (resize (empty) vector origin transform transform-inverse)))

(defn resize-parent-modifiers
  ([vector origin]
   (resize-parent (empty) vector origin))

  ([vector origin transform transform-inverse]
   (resize-parent (empty) vector origin transform transform-inverse)))

(defn rotation-modifiers
  [shape center angle]
  (let [shape-center (gco/shape->center shape)
        ;; Translation caused by the rotation
        move-vec
        (gpt/transform
         (gpt/point 0 0)
         (-> (gmt/matrix)
             (gmt/rotate angle center)
             (gmt/rotate (- angle) shape-center)))]

    (-> (empty)
        (rotation shape-center angle)
        (move move-vec))))

(defn remove-children-modifiers
  [shapes]
  (-> (empty)
      (remove-children shapes)))

(defn add-children-modifiers
  [shapes index]
  (-> (empty)
      (add-children shapes index)))

(defn reflow-modifiers
  []
  (-> (empty)
      (reflow)))

(defn scale-content-modifiers
  [value]
  (-> (empty)
      (scale-content value)))

(defn change-size
  [{:keys [selrect points transform transform-inverse] :as shape} width height]
  (let [old-width  (-> selrect :width)
        old-height (-> selrect :height)
        width      (or width old-width)
        height     (or height old-height)
        origin     (first points)
        scalex     (/ width old-width)
        scaley     (/ height old-height)]
    (resize-modifiers (gpt/point scalex scaley) origin transform transform-inverse)))

(defn change-dimensions-modifiers
  ([shape attr value]
   (change-dimensions-modifiers shape attr value nil))

  ([{:keys [transform transform-inverse] :as shape} attr value {:keys [ignore-lock?] :or {ignore-lock? false}}]
   (dm/assert! (map? shape))
   (dm/assert! (#{:width :height} attr))
   (dm/assert! (number? value))

   (let [;; Avoid havig shapes with zero size
         value (if (< (mth/abs value) 0.01)
                 0.01
                 value)

         {:keys [proportion proportion-lock]} shape
         size (select-keys (:selrect shape) [:width :height])
         new-size (if-not (and (not ignore-lock?) proportion-lock)
                    (assoc size attr value)
                    (if (= attr :width)
                      (-> size
                          (assoc :width value)
                          (assoc :height (/ value proportion)))
                      (-> size
                          (assoc :height value)
                          (assoc :width (* value proportion)))))

         width (:width new-size)
         height (:height new-size)

         {sr-width :width sr-height :height} (:selrect shape)

         origin (-> shape :points first)
         scalex (/ width sr-width)
         scaley (/ height sr-height)]

     (resize-modifiers (gpt/point scalex scaley) origin transform transform-inverse))))

(defn change-orientation-modifiers
  [shape orientation]
  (dm/assert! (map? shape))
  (dm/assert!
   "expected a valid orientation"
   (#{:horiz :vert} orientation))

  (let [width (:width shape)
        height (:height shape)
        new-width (if (= orientation :horiz) (max width height) (min width height))
        new-height (if (= orientation :horiz) (min width height) (max width height))

        shape-transform (:transform shape)
        shape-transform-inv (:transform-inverse shape)
        shape-center (gco/shape->center shape)
        {sr-width :width sr-height :height} (:selrect shape)

        origin (cond-> (gpt/point (:selrect shape))
                 (some? shape-transform)
                 (gmt/transform-point-center shape-center shape-transform))

        scalev (gpt/divide (gpt/point new-width new-height)
                           (gpt/point sr-width sr-height))]

    (resize-modifiers scalev origin shape-transform shape-transform-inv)))

;; Predicates

(defn empty?
  [modifiers]
  (and (c/empty? (dm/get-prop modifiers :geometry-child))
       (c/empty? (dm/get-prop modifiers :geometry-parent))
       (c/empty? (dm/get-prop modifiers :structure-parent))
       (c/empty? (dm/get-prop modifiers :structure-child))))

(defn child-modifiers?
  [modifiers]
  (or (d/not-empty? (dm/get-prop modifiers :geometry-child))
      (d/not-empty? (dm/get-prop modifiers :structure-child))))

(defn has-geometry?
  [modifiers]
  (or (d/not-empty? (dm/get-prop modifiers :geometry-parent))
      (d/not-empty? (dm/get-prop modifiers :geometry-child))))

(defn has-structure?
  [{:keys [structure-parent structure-child]}]
  (or (d/not-empty? structure-parent)
      (d/not-empty? structure-child)))

(defn has-structure-child?
  [modifiers]
  (d/not-empty? (dm/get-prop modifiers :structure-child)))

(defn only-move?
  "Returns true if there are only move operations"
  [modifiers]
  (let [move-op? #(= :move (dm/get-prop % :type))]
    (and (not (has-structure? modifiers))
         (every? move-op? (dm/get-prop modifiers :geometry-child))
         (every? move-op? (dm/get-prop modifiers :geometry-parent)))))

;; Extract subsets of modifiers

(defn select-child
  [modifiers]
  (assoc (or modifiers (empty)) :geometry-parent [] :structure-parent []))

(defn select-parent
  [modifiers]
  (assoc (or modifiers (empty)) :geometry-child [] :structure-child []))

(defn select-structure
  [modifiers]
  (assoc (or modifiers (empty)) :geometry-child [] :geometry-parent []))

(defn select-geometry
  [modifiers]
  (assoc (or modifiers (empty)) :structure-child [] :structure-parent []))

(defn select-child-geometry-modifiers
  [modifiers]
  (-> modifiers select-child select-geometry))

(defn select-child-structre-modifiers
  [modifiers]
  (-> modifiers select-child select-structure))

(defn added-children-frames
  "Returns the frames that have an 'add-children' operation"
  [modif-tree]
  (let [structure-changes
        (into {}
              (comp (filter (fn [[_ val]] (-> val :modifiers :structure-parent some?)))
                    (map (fn [[key val]]
                           [key (-> val :modifiers :structure-parent)])))
              modif-tree)]
    (into []
          (mapcat (fn [[frame-id changes]]
                    (->> changes
                         (filter (fn [{:keys [type]}] (= type :add-children)))
                         (mapcat (fn [{:keys [value]}]
                                   (->> value (map (fn [id] {:frame frame-id :shape id}))))))))
          structure-changes)))

;; Main transformation functions

(defn transform-move!
  "Transforms a matrix by the translation modifier"
  [matrix modifier]
  (-> (dm/get-prop modifier :vector)
      (gmt/translate-matrix)
      (gmt/multiply! matrix)))


(defn transform-resize!
  "Transforms a matrix by the resize modifier"
  [matrix modifier]
  (let [tf     (dm/get-prop modifier :transform)
        tfi    (dm/get-prop modifier :transform-inverse)
        vector (dm/get-prop modifier :vector)
        origin (dm/get-prop modifier :origin)
        origin (if ^boolean (some? tfi)
                 (gpt/transform origin tfi)
                 origin)]

    (gmt/multiply!
     (-> (gmt/matrix)
         (cond-> ^boolean (some? tf)
           (gmt/multiply! tf))
         (gmt/translate! origin)
         (gmt/scale! vector)
         (gmt/translate! (gpt/negate origin))
         (cond-> ^boolean (some? tfi)
           (gmt/multiply! tfi)))
     matrix)))

(defn transform-rotate!
  "Transforms a matrix by the rotation modifier"
  [matrix modifier]
  (let [center   (dm/get-prop modifier :center)
        rotation (dm/get-prop modifier :rotation)]
    (gmt/multiply!
     (-> (gmt/matrix)
         (gmt/translate! center)
         (gmt/multiply! (gmt/rotate-matrix rotation))
         (gmt/translate! (gpt/negate center)))
     matrix)))

(defn transform!
  "Returns a matrix transformed by the modifier"
  [matrix modifier]
  (let [type (dm/get-prop modifier :type)]
    (case type
      :move (transform-move! matrix modifier)
      :resize (transform-resize! matrix modifier)
      :rotation (transform-rotate! matrix modifier))))

(defn modifiers->transform1
  "A multiplatform version of modifiers->transform."
  [modifiers]
  (reduce transform! (gmt/matrix) modifiers))

(defn modifiers->transform
  "Given a set of modifiers returns its transformation matrix"
  [modifiers]
  (let [modifiers (concat (dm/get-prop modifiers :geometry-parent)
                          (dm/get-prop modifiers :geometry-child))
        modifiers (sort-by #(dm/get-prop % :order) modifiers)]
    (modifiers->transform1 modifiers)))

(defn modifiers->transform-old
  "Given a set of modifiers returns its transformation matrix"
  [modifiers]
  (let [modifiers (->> (concat (dm/get-prop modifiers :geometry-parent)
                               (dm/get-prop modifiers :geometry-child))
                       (sort-by :order))]

    (loop [matrix    (gmt/matrix)
           modifiers (seq modifiers)]
      (if (c/empty? modifiers)
        matrix
        (let [modifier (first modifiers)
              type   (dm/get-prop modifier :type)

              matrix
              (case type
                :move
                (-> (dm/get-prop modifier :vector)
                    (gmt/translate-matrix)
                    (gmt/multiply! matrix))

                :resize
                (let [tf     (dm/get-prop modifier :transform)
                      tfi    (dm/get-prop modifier :transform-inverse)
                      vector (dm/get-prop modifier :vector)
                      origin (dm/get-prop modifier :origin)
                      origin (if ^boolean (some? tfi)
                               (gpt/transform origin tfi)
                               origin)]

                  (gmt/multiply!
                   (-> (gmt/matrix)
                       (cond-> ^boolean (some? tf)
                         (gmt/multiply! tf))
                       (gmt/translate! origin)
                       (gmt/scale! vector)
                       (gmt/translate! (gpt/negate origin))
                       (cond-> ^boolean (some? tfi)
                         (gmt/multiply! tfi)))
                   matrix))

                :rotation
                (let [center   (dm/get-prop modifier :center)
                      rotation (dm/get-prop modifier :rotation)]
                  (gmt/multiply!
                   (-> (gmt/matrix)
                       (gmt/translate! center)
                       (gmt/multiply! (gmt/rotate-matrix rotation))
                       (gmt/translate! (gpt/negate center)))
                   matrix)))]
          (recur matrix (next modifiers)))))))

(defn transform-text-node [value attrs]
  (let [font-size   (-> (get attrs :font-size 14) d/parse-double (* value) str)
        letter-spacing (-> (get attrs :letter-spacing 0) d/parse-double (* value) str)]
    (d/txt-merge attrs {:font-size font-size
                        :letter-spacing letter-spacing})))

(defn transform-paragraph-node [value attrs]
  (let [font-size   (-> (get attrs :font-size 14) d/parse-double (* value) str)]
    (d/txt-merge attrs {:font-size font-size})))


(defn update-text-content
  [shape scale-text-content value]
  (update shape :content scale-text-content value))

(defn scale-text-content
  [content value]
  (->> content
       (txt/transform-nodes txt/is-text-node? (partial transform-text-node value))
       (txt/transform-nodes txt/is-paragraph-node? (partial transform-paragraph-node value))))

(defn apply-scale-content
  [shape value]
  ;; Scale can only be positive
  (let [value (mth/abs value)]
    (cond-> shape
      (cfh/text-shape? shape)
      (update-text-content scale-text-content value)

      :always
      (gsc/update-corners-scale value)

      (d/not-empty? (:strokes shape))
      (gss/update-strokes-width value)

      (d/not-empty? (:shadow shape))
      (gse/update-shadows-scale value)

      (some? (:blur shape))
      (gse/update-blur-scale value)

      (ctl/flex-layout? shape)
      (ctl/update-flex-scale value)

      (ctl/grid-layout? shape)
      (ctl/update-grid-scale value)

      :always
      (ctl/update-flex-child value))))

(defn remove-children-set
  [shapes children-to-remove]
  (let [remove? (set children-to-remove)]
    (d/removev remove? shapes)))

(defn apply-modifier
  [shape operation]
  (let [type (dm/get-prop operation :type)]
    (case type
      :rotation
      (let [rotation (dm/get-prop operation :value)]
        (update shape :rotation #(mod (+ (or % 0) rotation) 360)))

      :add-children
      (let [value (dm/get-prop operation :value)
            index (dm/get-prop operation :index)

            shape
            (if (some? index)
              (update shape :shapes
                      (fn [shapes]
                        (if (vector? shapes)
                          (d/insert-at-index shapes index value)
                          (d/concat-vec shapes value))))
              (update shape :shapes d/concat-vec value))]

        ;; Remove duplication
        (update shape :shapes #(into [] (apply d/ordered-set %))))

      :remove-children
      (let [value (dm/get-prop operation :value)]
        (update shape :shapes remove-children-set value))

      :scale-content
      (let [value (dm/get-prop operation :value)]
        (apply-scale-content shape value))

      :change-property
      (let [property (dm/get-prop operation :property)
            value (dm/get-prop operation :value)]
        (assoc shape property value))

      ;; :default => no change to shape
      shape)))

(defn apply-structure-modifiers
  "Apply structure changes to a shape"
  [shape modifiers]
  (as-> shape $
    (reduce apply-modifier $ (dm/get-prop modifiers :structure-parent))
    (reduce apply-modifier $ (dm/get-prop modifiers :structure-child))))
