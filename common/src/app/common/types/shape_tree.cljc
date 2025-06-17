;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape-tree
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))


;; FIXME: the order of arguments seems arbitrary, container should be a first artgument
(defn add-shape
  "Insert a shape in the tree, at the given index below the given parent or frame.
  Update the parent as needed."
  [id shape container frame-id parent-id index ignore-touched]
  (let [update-parent-shapes
        (fn [shapes]
          ;; Ensure that shapes is always a vector.
          (let [shapes (into [] shapes)]
            (cond
              (some #{id} shapes)
              shapes

              (nil? index)
              (conj shapes id)

              :else
              (d/insert-at-index shapes index [id]))))

        update-parent
        (fn [parent]
          (-> parent
              (update :shapes update-parent-shapes)
              (update :shapes d/vec-without-nils)
              (cond-> (and (ctk/in-component-copy? parent) (not ignore-touched))
                (dissoc :remote-synced))))

        update-objects
        (fn [objects parent-id]
          (let [parent-id (if (contains? objects parent-id)
                            parent-id
                            uuid/zero)
                frame-id (if (contains? objects frame-id)
                           frame-id
                           uuid/zero)]
            (-> objects
                (assoc id (-> shape
                              (assoc :frame-id frame-id)
                              (assoc :parent-id parent-id)
                              (assoc :id id)))
                (update parent-id update-parent))))

        parent-id (or parent-id frame-id)]

    (update container :objects update-objects parent-id)))

(defn parent-of?
  [parent child]
  (= (:id parent) (:parent-id child)))

(defn get-shape
  "Get a shape identified by id"
  [container id]
  (-> container :objects (get id)))

(defn set-shape
  "Replace a shape in the tree with a new one"
  [container shape]
  (assoc-in container [:objects (:id shape)] shape))

(defn delete-shape
  "Remove a shape and all its children from the tree.

   Remove it also from its parent, and marks it as touched
   if needed, unless ignore-touched is true."
  ([container shape-id]
   (delete-shape container shape-id false))

  ([container shape-id ignore-touched]
   (letfn [(delete-from-parent [parent]
             (let [parent (update parent :shapes d/without-obj shape-id)]
               (cond-> parent
                 (and (:shape-ref parent) (not ignore-touched))
                 (dissoc :remote-synced))))

           (delete-from-objects [objects]
             (if-let [target (get objects shape-id)]
               (let [parent-id    (or (:parent-id target)
                                      (:frame-id target))
                     children-ids (cfh/get-children-ids objects shape-id)]
                 (-> (reduce dissoc objects (cons shape-id children-ids))
                     (d/update-when parent-id delete-from-parent)))
               objects))]

     (update container :objects delete-from-objects))))

(defn fix-broken-children
  "Checks and fix the children relations of the shape. If a children does not
  exists on the objects tree, it will be removed from shape."
  [{:keys [objects] :as container} id]
  (let [contains? (partial contains? objects)]
    (d/update-in-when container [:objects id :shapes]
                      (fn [shapes]
                        (into [] (filter contains?) shapes)))))

(defn get-frames
  "Retrieves all frame objects as vector"
  ([objects] (get-frames objects nil))
  ([objects {:keys [skip-components? skip-copies?]
             :or {skip-components? false
                  skip-copies? false}}]
   (->> (or (-> objects meta ::index-frames)
            (let [lookup (d/getf objects)
                  xform  (comp (remove #(= uuid/zero %))
                               (keep lookup)
                               (filter cfh/frame-shape?))]
              (->> (keys objects)
                   (sequence xform))))
        (remove #(or (and ^boolean skip-components?
                          ^boolean (ctk/instance-head? %))
                     (and ^boolean skip-copies?
                          (and ^boolean (ctk/instance-head? %)
                               (not ^boolean (ctk/main-instance? %)))))))))

(defn get-frames-ids
  "Retrieves all frame ids as vector"
  ([objects] (get-frames-ids objects nil))
  ([objects options]
   (->> (get-frames objects options)
        (map :id))))

(defn get-nested-frames
  [objects frame-id]
  (into #{}
        (comp (filter cfh/frame-shape?)
              (map :id))
        (cfh/get-children objects frame-id)))

(defn get-root-frames-ids
  "Retrieves all frame objects as vector. It is not implemented in
  function of `cfh/get-immediate-children` for performance
  reasons. This function is executed in the render hot path."
  [objects]
  (let [add-frame
        (fn [result shape]
          (cond-> result
            (cfh/frame-shape? shape)
            (conj (:id shape))))]
    (cfh/reduce-objects objects (complement cfh/frame-shape?) add-frame [])))

(defn get-root-objects
  "Get all the objects under the root object"
  [objects]
  (let [add-shape
        (fn [result shape]
          (conj result shape))]
    (cfh/reduce-objects objects (complement cfh/frame-shape?) add-shape [])))

(defn get-root-shapes
  "Get all shapes that are not frames"
  [objects]
  (let [add-shape
        (fn [result shape]
          (cond-> result
            (not (cfh/frame-shape? shape))
            (conj shape)))]
    (cfh/reduce-objects objects (complement cfh/frame-shape?) add-shape [])))

(defn get-root-shapes-ids
  [objects]
  (->> (get-root-shapes objects)
       (mapv :id)))

(defn- get-base
  [id-a id-b id-parents]

  (let [[parents-a parents-a-index] (get id-parents id-a)
        [parents-b parents-b-index] (get id-parents id-b)

        parents-a (cons id-a parents-a)
        parents-b (into #{id-b} parents-b)

        ;; Search for the common parent (frame or group) in order
        base-id (or (d/seek parents-b parents-a) uuid/zero)

        idx-a (get parents-a-index base-id)
        idx-b (get parents-b-index base-id)]

    [base-id idx-a idx-b]))

(defn- is-shape-over-shape?
  [objects base-shape-id over-shape-id bottom-frames? id-parents]

  (let [[base-id index-a index-b] (get-base base-shape-id over-shape-id id-parents)]
    (cond
      ;; The base the base shape, so the other item is below (if not bottom-frames)
      (= base-id base-shape-id)
      (and ^boolean bottom-frames?
           ^boolean (cfh/frame-shape? objects base-id))

      ;; The base is the testing over, so it's over (if not bottom-frames)
      (= base-id over-shape-id)
      (or (not ^boolean bottom-frames?)
          (not ^boolean (cfh/frame-shape? objects base-id)))

      ;; Check which index is lower
      :else
      ;; If the base is a layout we should check if the z-index property is set
      (let [layer-order? (ctl/any-layout? objects base-id)
            [z-index-a z-index-b]
            (if layer-order?
              [(ctl/layout-z-index objects (dm/get-in objects [base-id :shapes index-a]))
               (ctl/layout-z-index objects (dm/get-in objects [base-id :shapes index-b]))]
              [0 0])]

        (cond
          (and (= z-index-a z-index-b) (not layer-order?))
          (< index-a index-b)

          (and (= z-index-a z-index-b) layer-order?)
          (> index-a index-b)

          :else
          (< z-index-a z-index-b))))))

(defn sort-z-index
  ([objects ids]
   (sort-z-index objects ids nil))

  ([objects ids {:keys [bottom-frames?] :as options
                 :or   {bottom-frames? false}}]
   ;; Create an index of the parents of the shapes. This will speed the sorting because we use
   ;; this information down the line.
   (let [id-parents (into {} (map #(vector % (cfh/get-parent-ids-with-index objects %))) ids)]
     (letfn [(comp [id-a id-b]
               (cond
                 (= id-a id-b)
                 0

                 (is-shape-over-shape? objects id-a id-b bottom-frames? id-parents)
                 1

                 :else
                 -1))]
       (sort comp ids)))))

(defn sort-z-index-objects
  ([objects items]
   (sort-z-index-objects objects items nil))
  ([objects items {:keys [bottom-frames?]
                   :or   {bottom-frames? false}}]
   (let [id-parents (into {} (map #(vector (dm/get-prop % :id) (cfh/get-parent-ids-with-index objects (dm/get-prop % :id)))) items)]
     (d/unstable-sort
      (fn [obj-a obj-b]
        (let [id-a (dm/get-prop obj-a :id)
              id-b (dm/get-prop obj-b :id)]
          (if (= id-a id-b)
            0
            (if ^boolean (is-shape-over-shape? objects id-a id-b bottom-frames? id-parents)
              1
              -1))))
      items))))

(defn get-frame-by-position
  ([objects position]
   (get-frame-by-position objects position nil))

  ([objects position options]
   (dm/assert!
    "expected a point"
    (gpt/point? position))

   (let [frames    (get-frames objects options)
         frames    (sort-z-index-objects objects frames options)
         ;; Validator is a callback to add extra conditions to the suggested frame
         validator (or (get options :validator) #(-> true))]
     (or (d/seek #(and ^boolean (some? position)
                       ^boolean (gsh/has-point? % position)
                       ^boolean (validator %))
                 frames)
         (get objects uuid/zero)))))

(defn get-frame-id-by-position
  ([objects position] (get-frame-id-by-position objects position nil))
  ([objects position options]
   (when-let [frame (get-frame-by-position objects position options)]
     (dm/get-prop frame :id))))

(defn get-frames-by-position
  ([objects position] (get-frames-by-position objects position nil))
  ([objects position options]
   (->> (get-frames objects options)
        (filter #(and ^boolean (some? position)
                      ^boolean (gsh/has-point? % position)))
        (sort-z-index-objects objects))))

(defn top-nested-frame
  "Search for the top nested frame for positioning shapes when moving or creating.
  Looks for all the frames in a position and then goes in depth between the top-most and its
  children to find the target."
  ([objects position]
   (top-nested-frame objects position nil))

  ([objects position excluded]
   (assert (or (nil? excluded) (set? excluded)))

   (let [frames (cond->> (get-frames-by-position objects position)
                  (some? excluded)
                  (remove (fn [obj]
                            (let [id (dm/get-prop obj :id)]
                              (contains? excluded id))))

                  :always
                  (remove #(or ^boolean (true? (:hidden %))
                               ^boolean (true? (:blocked %)))))

         frame-set (into #{} (map #(dm/get-prop % :id)) frames)]

     (loop [current-shape (first frames)]
       (let [child-frame-id (d/seek #(contains? frame-set %)
                                    (reverse (:shapes current-shape)))]
         (if (nil? child-frame-id)
           (or (:id current-shape) uuid/zero)
           (recur (get objects child-frame-id))))))))

(defn get-viewer-frames
  ([objects]
   (get-viewer-frames objects nil))

  ([objects {:keys [all-frames?]}]
   (->> (get-frames objects)
        (sort-z-index-objects objects)
        (into []
              (if all-frames?
                (map identity)
                (remove :hide-in-viewer))))))

(defn start-page-index
  [objects]
  (with-meta objects {::index-frames (get-frames (with-meta objects nil))}))

(defn update-page-index
  [objects]
  (with-meta objects {::index-frames (get-frames (with-meta objects nil))}))

(defn update-object-indices
  [file page-id]
  (update-in file [:pages-index page-id :objects] update-page-index))

(defn rotated-frame?
  [frame]
  (not (mth/almost-zero? (:rotation frame 0))))

(defn clone-shape
  "Gets a copy of the shape and all its children, with new ids and with
  the parent-children links correctly set. Admits functions to make
  more transformations to the cloned shapes and the original ones.

  Returns the cloned shape, the list of all new shapes (including
  the cloned one), and possibly a list of original shapes modified.

  The list of shapes are returned in tree traversal order, respecting
  the order of the children of each parent."
  [shape parent-id objects & {:keys [update-new-shape update-original-shape force-id keep-ids? frame-id dest-objects]
                              :or {update-new-shape (fn [shape _] shape)
                                   update-original-shape (fn [shape _] shape)
                                   force-id nil
                                   keep-ids? false
                                   frame-id nil
                                   dest-objects objects}}]
  (let [new-id (cond
                 (some? force-id) force-id
                 keep-ids? (:id shape)
                 :else (uuid/next))

         ;; Assign the correct frame-id for the given parent. It's the parent-id (if parent is frame)
         ;; or the parent's frame-id otherwise. Only for the first cloned shapes. In recursive calls
         ;; this is not needed.
        frame-id (cond
                   (and (nil? frame-id) (cfh/frame-shape? dest-objects parent-id))
                   parent-id

                   (nil? frame-id)
                   (dm/get-in dest-objects [parent-id :frame-id] uuid/zero)

                   :else
                   frame-id)]

    (loop [child-ids (seq (:shapes shape))
           new-direct-children []
           new-children []
           updated-children []]

      (if (empty? child-ids)
        (let [new-shape (cond-> shape
                          :always
                          (assoc :id new-id
                                 :parent-id parent-id
                                 :frame-id frame-id)

                          :always
                          ;; Store in the meta the old id so we can do a remap afterwards
                          ;; in the parent
                          (with-meta {::old-id (:id shape)})

                          (some? (:shapes shape))
                          (assoc :shapes (mapv :id new-direct-children)))

              ;; For a GRID layout remap the cells shapes' old-id to the new id given in the clone
              new-shape
              (if (ctl/grid-layout? new-shape)
                (let [ids-map (into {} (map #(vector (-> % meta ::old-id) (:id %))) new-children)]
                  (ctl/remap-grid-cells new-shape ids-map))
                new-shape)

              new-shape  (update-new-shape new-shape shape)
              new-shapes (into [new-shape] new-children)

              updated-shape  (update-original-shape shape new-shape)
              updated-shapes (if (identical? shape updated-shape)
                               updated-children
                               (into [updated-shape] updated-children))]

          [new-shape new-shapes updated-shapes])

        (let [child-id       (first child-ids)
              child          (get objects child-id)
              _              (dm/assert! (some? child))
              frame-id-child (if (cfh/frame-shape? shape)
                               new-id
                               frame-id)

              [new-child new-child-shapes updated-child-shapes]
              (clone-shape child
                           new-id
                           objects
                           :update-new-shape update-new-shape
                           :update-original-shape update-original-shape
                           :force-id nil
                           :keep-ids? keep-ids?
                           :frame-id frame-id-child
                           :dest-objects dest-objects)]

          (recur
           (next child-ids)
           (into new-direct-children [new-child])
           (into new-children new-child-shapes)
           (into updated-children updated-child-shapes)))))))

(defn generate-shape-grid
  "Generate a sequence of positions that lays out the list of
  shapes in a grid of equal-sized rows and columns."
  [shapes start-position gap]
  (when (seq shapes)
    (let [bounds      (map gsh/bounding-box shapes)

          grid-size   (-> shapes count mth/sqrt mth/ceil)
          row-size    (+ (reduce d/max ##-Inf (map :height bounds)) gap)
          column-size (+ (reduce d/max ##-Inf (map :width bounds)) gap)

          get-next    (fn get-next [counter]
                        (let [row      (quot counter grid-size)
                              column   (mod counter grid-size)
                              position (->> (gpt/point (* column column-size)
                                                       (* row row-size))
                                            (gpt/add start-position))]
                          (cons position
                                (lazy-seq
                                 (get-next (inc counter))))))]
      (with-meta (get-next 0)
        {:width  (* grid-size column-size)
         :height (* grid-size row-size)}))))
