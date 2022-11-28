;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.modifiers
  (:refer-clojure :exclude [empty empty?])
  (:require
   [app.common.perf :as perf]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.text :as txt]
   #?(:cljs [cljs.core :as c]
      :clj [clojure.core :as c])))

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

;; Private aux functions

(def conjv (fnil conj []))

(defn- move-vec? [vector]
  (or (not (mth/almost-zero? (:x vector)))
      (not (mth/almost-zero? (:y vector)))))

(defn- resize-vec? [vector]
  (or (not (mth/almost-zero? (- (:x vector) 1)))
      (not (mth/almost-zero? (- (:y vector) 1)))))

(defn- mergeable-move?
  [op1 op2]
  (and (= :move (:type op1))
       (= :move (:type op2))))

(defn- mergeable-resize?
  [op1 op2]
  (and (= :resize (:type op1))
       (= :resize (:type op2))

       ;; Same transforms
       (gmt/close? (or (:transform op1) (gmt/matrix)) (or (:transform op2) (gmt/matrix)))
       (gmt/close? (or (:transform-inverse op1) (gmt/matrix)) (or (:transform-inverse op2) (gmt/matrix)))

       ;; Same origin
       (gpt/close? (:origin op1) (:origin op2))))

(defn- merge-move
  [op1 op2]
  {:type :move
   :vector (gpt/add (:vector op1) (:vector op2))})

(defn- merge-resize
  [op1 op2]
  (let [vector (gpt/point (* (-> op1 :vector :x) (-> op2 :vector :x))
                          (* (-> op1 :vector :y) (-> op2 :vector :y)))]
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
            (move-vec? (:vector item))
            (conj item)))
        (conj operations op)))))

(defn- maybe-add-resize
  "Check the last operation to check if we can stack it over the last one"
  [operations op]

  (if (c/empty? operations)
    [op]
    (let [head (peek operations)]
      (if (mergeable-resize? head op)
        (let [item (merge-resize head op)]
          (cond-> (pop operations)
            (resize-vec? (:vector item))
            (conj item)))
        (conj operations op)))))

(defn valid-vector?
  [{:keys [x y]}]
  (and (some? x)
       (some? y)
       (not (mth/nan? x))
       (not (mth/nan? y))))

;; Public builder API

(defn empty []
  {})

(defn move-parent
  ([modifiers x y]
   (move-parent modifiers (gpt/point x y)))

  ([modifiers vector]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (cond-> modifiers
     (move-vec? vector)
     (update :geometry-parent maybe-add-move {:type :move :vector vector}))))

(defn resize-parent
  ([modifiers vector origin]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (cond-> modifiers
     (resize-vec? vector)
     (update :geometry-parent maybe-add-resize {:type :resize
                                                :vector vector
                                                :origin origin})))

  ([modifiers vector origin transform transform-inverse]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (cond-> modifiers
     (resize-vec? vector)
     (update :geometry-parent maybe-add-resize {:type :resize
                                                :vector vector
                                                :origin origin
                                                :transform transform
                                                :transform-inverse transform-inverse}))))
(defn move
  ([modifiers x y]
   (move modifiers (gpt/point x y)))

  ([modifiers vector]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (cond-> modifiers
     (move-vec? vector)
     (update :geometry-child maybe-add-move {:type :move :vector vector}))))

(defn resize
  ([modifiers vector origin]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (cond-> modifiers
     (resize-vec? vector)
     (update :geometry-child maybe-add-resize {:type :resize
                                               :vector vector
                                               :origin origin})))

  ([modifiers vector origin transform transform-inverse]
   (assert (valid-vector? vector) (dm/str "Invalid move vector: " (:x vector) "," (:y vector)))
   (cond-> modifiers
     (resize-vec? vector)
     (update :geometry-child maybe-add-resize {:type :resize
                                               :vector vector
                                               :origin origin
                                               :transform transform
                                               :transform-inverse transform-inverse}))))

(defn rotation
  [modifiers center angle]
  (cond-> modifiers
    (not (mth/close? angle 0))
    (-> (update :structure-child conjv {:type :rotation
                                        :rotation angle})
        (update :geometry-child conjv {:type :rotation
                                       :center center
                                       :rotation angle}))))

(defn remove-children
  [modifiers shapes]
  (cond-> modifiers
    (d/not-empty? shapes)
    (update :structure-parent conjv {:type :remove-children
                                     :value shapes})))

(defn add-children
  [modifiers shapes index]
  (cond-> modifiers
    (d/not-empty? shapes)
    (update :structure-parent conjv {:type :add-children
                                     :value shapes
                                     :index index})))

(defn reflow
  [modifiers]
  (-> modifiers
      (update :structure-parent conjv {:type :reflow})))

(defn scale-content
  [modifiers value]
  (-> modifiers
      (update :structure-child conjv {:type :scale-content :value value})))

(defn change-property
  [modifiers property value]
  (-> modifiers
      (update :structure-child conjv {:type :change-property
                                      :property property
                                      :value value})))

(defn- merge-geometry
  [geometry other]

  (cond
    (c/empty? geometry)
    other

    (c/empty? other)
    geometry

    :else
    (loop [result geometry
           modifiers (seq other)]
      (if (c/empty? modifiers)
        result
        (let [current (first modifiers)
              result
              (cond
                (= :move (:type current))
                (maybe-add-move result current)

                (= :resize (:type current))
                (maybe-add-resize result current)

                :else
                (conj result current))]

          (recur result (rest modifiers)))))))

(defn add-modifiers
  [modifiers new-modifiers]

  (cond-> modifiers
    (some? (:geometry-child new-modifiers))
    (update :geometry-child merge-geometry (:geometry-child new-modifiers))

    (some? (:geometry-parent new-modifiers))
    (update :geometry-parent merge-geometry (:geometry-parent new-modifiers))

    (some? (:structure-parent new-modifiers))
    (update :structure-parent #(d/concat-vec [] % (:structure-parent new-modifiers)))

    (some? (:structure-child new-modifiers))
    (update :structure-child #(d/concat-vec [] % (:structure-child new-modifiers)))))


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
  (let [shape-center (gco/center-shape shape)
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

(defn change-dimensions-modifiers
  [{:keys [transform transform-inverse] :as shape} attr value]
  (us/assert map? shape)
  (us/assert #{:width :height} attr)
  (us/assert number? value)

  (let [{:keys [proportion proportion-lock]} shape
        size (select-keys (:selrect shape) [:width :height])
        new-size (if-not proportion-lock
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

    (resize-modifiers (gpt/point scalex scaley) origin transform transform-inverse)))

(defn change-orientation-modifiers
  [shape orientation]
  (us/assert map? shape)
  (us/verify #{:horiz :vert} orientation)
  (let [width (:width shape)
        height (:height shape)
        new-width (if (= orientation :horiz) (max width height) (min width height))
        new-height (if (= orientation :horiz) (min width height) (max width height))

        shape-transform (:transform shape)
        shape-transform-inv (:transform-inverse shape)
        shape-center (gco/center-shape shape)
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
  (and (c/empty? (:geometry-child modifiers))
       (c/empty? (:geometry-parent modifiers))
       (c/empty? (:structure-parent modifiers))
       (c/empty? (:structure-child modifiers))))

(defn child-modifiers?
  [{:keys [geometry-child structure-child]}]
  (or (d/not-empty? geometry-child)
      (d/not-empty? structure-child)))

(defn only-move?
  "Returns true if there are only move operations"
  [{:keys [geometry-child geometry-parent]}]
  (let [move-op? #(= :move (:type %))]
    (and (every? move-op? geometry-child)
         (every? move-op? geometry-parent))))

(defn has-geometry?
  [{:keys [geometry-parent geometry-child]}]
  (or (d/not-empty? geometry-parent)
      (d/not-empty? geometry-child)))

(defn has-structure?
  [{:keys [structure-parent structure-child]}]
  (or (d/not-empty? structure-parent)
      (d/not-empty? structure-child)))

;; Extract subsets of modifiers

(defn select-child-modifiers
  [modifiers]
  (select-keys modifiers [:geometry-child :structure-child]))

(defn select-child-geometry-modifiers
  [modifiers]
  (select-keys modifiers [:geometry-child]))

(defn select-parent-modifiers
  [modifiers]
  (select-keys modifiers [:geometry-parent :structure-parent]))

(defn select-structure
  [modifiers]
  (select-keys modifiers [:structure-parent :structure-child]))

(defn select-geometry
  [modifiers]
  (select-keys modifiers [:geometry-parent :geometry-child]))

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

(defn modifiers->transform
  "Given a set of modifiers returns its transformation matrix"
  [modifiers]

  (let [modifiers
        (if (d/not-empty? (:geometry-parent modifiers))
          (concat (:geometry-parent modifiers) (:geometry-child modifiers))
          (:geometry-child modifiers))]

    (when (d/not-empty? modifiers)
      (loop [matrix (gmt/matrix)
             modifiers (seq modifiers)]
        (if (c/empty? modifiers)
          matrix
          (let [{:keys [type vector rotation center origin transform transform-inverse] :as modifier} (first modifiers)
                matrix
                (case type
                  :move
                  (gmt/multiply (gmt/translate-matrix vector) matrix)

                  :resize
                  (let [origin (cond-> origin
                                 (or (some? transform-inverse)(some? transform))
                                 (gpt/transform transform-inverse))]
                    (gmt/multiply
                     (-> (gmt/matrix)
                         (cond-> (some? transform)
                           (gmt/multiply transform))
                         (gmt/translate origin)
                         (gmt/scale vector)
                         (gmt/translate (gpt/negate origin))
                         (cond-> (some? transform-inverse)
                           (gmt/multiply transform-inverse)))
                     matrix))

                  :rotation
                  (gmt/multiply
                   (-> (gmt/matrix)
                       (gmt/translate center)
                       (gmt/multiply (gmt/rotate-matrix rotation))
                       (gmt/translate (gpt/negate center)))
                   matrix))]
            (recur matrix (rest modifiers))))))))

(defn apply-structure-modifiers
  "Apply structure changes to a shape"
  [shape modifiers]
  (letfn [(scale-text-content
            [content value]

            (->> content
                 (txt/transform-nodes
                  txt/is-text-node?
                  (fn [attrs]
                    (let [font-size (-> (get attrs :font-size 14)
                                        (d/parse-double)
                                        (* value)
                                        (str)) ]
                      (d/txt-merge attrs {:font-size font-size}))))))

          (apply-scale-content
            [shape value]

            (cond-> shape
              (cph/text-shape? shape)
              (update :content scale-text-content value)))]
    (let [remove-children
          (fn [shapes children-to-remove]
            (let [remove? (set children-to-remove)]
              (d/removev remove? shapes)))

          apply-modifier
          (fn [shape {:keys [type property value index rotation]}]
            (cond-> shape
              (= type :rotation)
              (update :rotation #(mod (+ % rotation) 360))

              (and (= type :add-children) (some? index))
              (update :shapes
                      (fn [shapes]
                        (if (vector? shapes)
                          (cph/insert-at-index shapes index value)
                          (d/concat-vec shapes value))))

              (and (= type :add-children) (nil? index))
              (update :shapes d/concat-vec value)

              (= type :remove-children)
              (update :shapes remove-children value)

              (= type :scale-content)
              (apply-scale-content value)

              (= type :change-property)
              (assoc property value)))]

      (as-> shape $
        (reduce apply-modifier $ (:structure-parent modifiers))
        (reduce apply-modifier $ (:structure-child modifiers))))))
