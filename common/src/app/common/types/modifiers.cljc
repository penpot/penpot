;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.modifiers
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.text :as txt]))

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

(def conjv (fnil conj []))

;; Public builder API

(defn empty-modifiers []
  {})

(defn set-move-parent
  ([modifiers x y]
   (set-move-parent modifiers (gpt/point x y)))

  ([modifiers vector]
   (-> modifiers
       (update :geometry-parent conjv {:type :move :vector vector}))))

(defn set-resize-parent
  ([modifiers vector origin]
   (-> modifiers
       (update :geometry-parent conjv {:type :resize
                                       :vector vector
                                       :origin origin})))

  ([modifiers vector origin transform transform-inverse]
   (-> modifiers
       (update :geometry-parent conjv {:type :resize
                                       :vector vector
                                       :origin origin
                                       :transform transform
                                       :transform-inverse transform-inverse}))))
(defn set-move
  ([modifiers x y]
   (set-move modifiers (gpt/point x y)))

  ([modifiers vector]
   (-> modifiers
       (update :geometry-child conjv {:type :move :vector vector}))))

(defn set-resize
  ([modifiers vector origin]
   (-> modifiers
       (update :geometry-child conjv {:type :resize
                                      :vector vector
                                      :origin origin})))

  ([modifiers vector origin transform transform-inverse]
   (-> modifiers
       (update :geometry-child conjv {:type :resize
                                      :vector vector
                                      :origin origin
                                      :transform transform
                                      :transform-inverse transform-inverse}))))

(defn set-rotation
  [modifiers center angle]
  (-> modifiers
      (update :structure-child conjv {:type :rotation
                                      :rotation angle})
      (update :geometry-child conjv {:type :rotation
                                     :center center
                                     :rotation angle})))

(defn set-remove-children
  [modifiers shapes]
  (-> modifiers
      (update :structure-parent conjv {:type :remove-children
                                       :value shapes}))
  )

(defn set-add-children
  [modifiers shapes index]
  (-> modifiers
      (update :structure-parent conjv {:type :add-children
                                       :value shapes
                                       :index index})))

(defn set-reflow
  [modifiers]
  (-> modifiers
      (update :structure-parent conjv {:type :reflow})))

(defn set-scale-content
  [modifiers value]
  (-> modifiers
      (update :structure-child conjv {:type :scale-content :value value})))

(defn add-modifiers
  [modifiers new-modifiers]

  (cond-> modifiers
    (some? (:geometry-child new-modifiers))
    (update :geometry-child #(d/concat-vec [] % (:geometry-child new-modifiers)))

    (some? (:geometry-parent new-modifiers))
    (update :geometry-parent #(d/concat-vec [] % (:geometry-parent new-modifiers)))

    (some? (:structure-parent new-modifiers))
    (update :structure-parent #(d/concat-vec [] % (:structure-parent new-modifiers)))

    (some? (:structure-child new-modifiers))
    (update :structure-child #(d/concat-vec [] % (:structure-child new-modifiers)))))


;; These are convenience methods to create single operation modifiers without the builder

(defn move
  ([x y]
   (set-move (empty-modifiers) (gpt/point x y)))

  ([vector]
   (set-move (empty-modifiers) vector)))

(defn move-parent
  ([x y]
   (set-move-parent (empty-modifiers) (gpt/point x y)))

  ([vector]
   (set-move-parent (empty-modifiers) vector)))

(defn resize
  ([vector origin]
   (set-resize (empty-modifiers) vector origin))

  ([vector origin transform transform-inverse]
   (set-resize (empty-modifiers) vector origin transform transform-inverse)))

(defn resize-parent
  ([vector origin]
   (set-resize-parent (empty-modifiers) vector origin))

  ([vector origin transform transform-inverse]
   (set-resize-parent (empty-modifiers) vector origin transform transform-inverse)))

(defn rotation
  [shape center angle]
  (let [shape-center (gco/center-shape shape)
        rotation (-> (gmt/matrix)
                     (gmt/rotate angle center)
                     (gmt/rotate (- angle) shape-center))]

    (-> (empty-modifiers)
        (set-rotation shape-center angle)
        (set-move (gpt/transform (gpt/point 0 0) rotation)))))

(defn remove-children
  [shapes]
  (-> (empty-modifiers)
      (set-remove-children shapes)))

(defn add-children
  [shapes index]
  (-> (empty-modifiers)
      (set-add-children shapes index)))

(defn reflow
  []
  (-> (empty-modifiers)
      (set-reflow)))

(defn scale-content
  [value]
  (-> (empty-modifiers)
      (set-scale-content value)))

(defn child-modifiers?
  [{:keys [geometry-child structure-child]}]
  (or (d/not-empty? geometry-child)
      (d/not-empty? structure-child)))

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
  (select-keys modifiers [:structure-parent]))

(defn empty-modifiers?
  [modifiers]
  (and (empty? (:geometry-child modifiers))
       (empty? (:geometry-parent modifiers))
       (empty? (:structure-parent modifiers))
       (empty? (:structure-child modifiers))))

(defn change-dimensions
  [shape attr value]
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

        shape-transform (:transform shape)
        shape-transform-inv (:transform-inverse shape)
        shape-center (gco/center-shape shape)
        {sr-width :width sr-height :height} (:selrect shape)

        origin (cond-> (gpt/point (:selrect shape))
                 (some? shape-transform)
                 (gmt/transform-point-center shape-center shape-transform))

        scalev (gpt/divide (gpt/point width height)
                           (gpt/point sr-width sr-height))]

    (resize scalev origin shape-transform shape-transform-inv)))

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

    (resize scalev origin shape-transform shape-transform-inv)))

(defn merge-modifiers
  [objects modifiers]

  (let [set-modifier
        (fn [objects [id modifiers]]
          (-> objects
              (d/update-when id merge modifiers)))]
    (->> modifiers
         (reduce set-modifier objects))))

(defn only-move?
  [modifier]
  (or (and (= 1 (-> modifier :geometry-child count))
           (= :move (-> modifier :geometry-child first :type)))
      (and (= 1 (-> modifier :geometry-parent count))
           (= :move (-> modifier :geometry-parent first :type)))))

(defn get-frame-add-children
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

(defn modifiers->transform
  [modifiers]
  (letfn [(apply-modifier [matrix {:keys [type vector rotation center origin transform transform-inverse] :as modifier}]
            (case type
              :move
              (gmt/multiply (gmt/translate-matrix vector) matrix)

              :resize
              (gmt/multiply
               (-> (gmt/matrix)
                   (gmt/translate origin)
                   (cond-> (some? transform)
                     (gmt/multiply transform))
                   (gmt/scale vector)
                   (cond-> (some? transform-inverse)
                     (gmt/multiply transform-inverse))
                   (gmt/translate (gpt/negate origin)))
               matrix)

              :rotation
              ;; TODO LAYOUT: Maybe an issue when no center data
              (gmt/multiply
               (-> (gmt/matrix)
                   (gmt/translate center)
                   (gmt/multiply (gmt/rotate-matrix rotation))
                   (gmt/translate (gpt/negate center)))
               matrix)))]
    (let [modifiers (if (d/not-empty? (:geometry-parent modifiers))
                      (d/concat-vec (:geometry-parent modifiers) (:geometry-child modifiers))
                      (:geometry-child modifiers))]
      (->> modifiers
           (reduce apply-modifier (gmt/matrix))))))

(defn scale-text-content
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

(defn apply-scale-content
  [shape value]

  (cond-> shape
    (cph/text-shape? shape)
    (update :content scale-text-content value)))

(defn apply-structure-modifiers
  [shape modifiers]
  (let [remove-children
        (fn [shapes children-to-remove]
          (let [remove? (set children-to-remove)]
            (d/removev remove? shapes)))

        apply-modifier
        (fn [shape {:keys [type value index rotation]}]
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
            (apply-scale-content value)))]

    (as-> shape $
      (reduce apply-modifier $ (:structure-parent modifiers))
      (reduce apply-modifier $ (:structure-child modifiers)))))

(defn has-geometry?
  [{:keys [geometry-parent geometry-child]}]
  (or (d/not-empty? geometry-parent)
      (d/not-empty? geometry-child)))
