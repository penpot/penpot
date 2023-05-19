;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape-tree
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

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
              (cond-> (and (:shape-ref parent)
                           (not= (:id parent) frame-id)
                           (not ignore-touched))
                (-> (update :touched cph/set-touched-group :shapes-group)
                    (dissoc :remote-synced?)))))

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
                 (-> (update :touched cph/set-touched-group :shapes-group)
                     (dissoc :remote-synced?)))))

           (delete-from-objects [objects]
             (if-let [target (get objects shape-id)]
               (let [parent-id     (or (:parent-id target)
                                       (:frame-id target))
                     children-ids  (cph/get-children-ids objects shape-id)]
                 (-> (reduce dissoc objects children-ids)
                     (dissoc shape-id)
                     (d/update-when parent-id delete-from-parent)))
               objects))]

     (update container :objects delete-from-objects))))

(defn get-frames
  "Retrieves all frame objects as vector"
  ([objects] (get-frames objects nil))
  ([objects {:keys [skip-components? skip-copies?] :or {skip-components? false skip-copies? false}}]
   (->> (or (-> objects meta ::index-frames)
            (let [lookup (d/getf objects)
                  xform  (comp (remove #(= uuid/zero %))
                               (keep lookup)
                               (filter cph/frame-shape?))]
              (->> (keys objects)
                   (into [] xform))))
        (remove #(or (and skip-components? (ctk/instance-head? %))
                     (and skip-copies? (and (ctk/instance-head? %) (not (ctk/main-instance? %)))))))))

(defn get-frames-ids
  "Retrieves all frame ids as vector"
  ([objects] (get-frames-ids objects nil))
  ([objects options]
   (->> (get-frames objects options)
        (mapv :id))))

(defn get-nested-frames
  [objects frame-id]
  (into #{}
        (comp (filter cph/frame-shape?)
              (map :id))
        (cph/get-children objects frame-id)))

(defn get-root-frames-ids
  "Retrieves all frame objects as vector. It is not implemented in
  function of `cph/get-immediate-children` for performance
  reasons. This function is executed in the render hot path."
  [objects]
  (let [add-frame
        (fn [result shape]
          (cond-> result
            (cph/frame-shape? shape)
            (conj (:id shape))))]
    (cph/reduce-objects objects (complement cph/frame-shape?) add-frame [])))

(defn get-root-objects
  "Get all the objects under the root object"
  [objects]
  (let [add-shape
        (fn [result shape]
          (conj result shape))]
    (cph/reduce-objects objects (complement cph/frame-shape?) add-shape [])))

(defn get-root-shapes
  "Get all shapes that are not frames"
  [objects]
  (let [add-shape
        (fn [result shape]
          (cond-> result
            (not (cph/frame-shape? shape))
            (conj shape)))]
    (cph/reduce-objects objects (complement cph/frame-shape?) add-shape [])))

(defn get-root-shapes-ids
  [objects]
  (->> (get-root-shapes objects)
       (mapv :id)))

(defn get-base
  [objects id-a id-b]

  (let [[parents-a parents-a-index] (cph/get-parent-ids-with-index objects id-a)
        [parents-b parents-b-index] (cph/get-parent-ids-with-index objects id-b)

        parents-a (cons id-a parents-a)
        parents-b (into #{id-b} parents-b)

        ;; Search for the common parent (frame or group) in order
        base-id (or (d/seek parents-b parents-a) uuid/zero)

        idx-a (get parents-a-index base-id)
        idx-b (get parents-b-index base-id)]

    [base-id idx-a idx-b]))

(defn is-shape-over-shape?
  [objects base-shape-id over-shape-id bottom-frames?]

  (let [[base-id index-a index-b] (get-base objects base-shape-id over-shape-id)]
    (cond
      ;; The base the base shape, so the other item is below (if not bottom-frames)
      (= base-id base-shape-id)
      (and bottom-frames? (cph/frame-shape? objects base-id))

      ;; The base is the testing over, so it's over (if not bottom-frames)
      (= base-id over-shape-id)
      (or (not bottom-frames?) (not (cph/frame-shape? objects base-id)))

      ;; Check which index is lower
      :else
      ;; If the base is a layout we should check if the z-index property is set
      (let [[z-index-a z-index-b]
            (if (ctl/any-layout? objects base-id)
              [(ctl/layout-z-index objects (dm/get-in objects [base-id :shapes index-a]))
               (ctl/layout-z-index objects (dm/get-in objects [base-id :shapes index-b]))]
              [0 0])]

        (if (= z-index-a z-index-b)
          (< index-a index-b)
          (< z-index-a z-index-b))))))

(defn sort-z-index
  ([objects ids]
   (sort-z-index objects ids nil))

  ([objects ids {:keys [bottom-frames?] :as options
                 :or   {bottom-frames? false}}]
   (letfn [
           (comp [id-a id-b]
             (cond
               (= id-a id-b)
               0

               (is-shape-over-shape? objects id-a id-b bottom-frames?)
               1

               :else
               -1))]
     (sort comp ids))))

(defn frame-id-by-position
  ([objects position] (frame-id-by-position objects position nil))
  ([objects position options]
   (assert (gpt/point? position))
   (let [top-frame
         (->> (get-frames-ids objects options)
              (sort-z-index objects)
              (d/seek #(and position (gsh/has-point? (get objects %) position))))]
     (or top-frame uuid/zero))))

(defn frame-by-position
  ([objects position] (frame-by-position objects position nil))
  ([objects position options]
   (let [frame-id (frame-id-by-position objects position options)]
     (get objects frame-id))))

(defn all-frames-by-position
  ([objects position] (all-frames-by-position objects position nil))
  ([objects position options]
   (->> (get-frames-ids objects options)
        (filter #(and position (gsh/has-point? (get objects %) position)))
        (sort-z-index objects))))

(defn top-nested-frame
  "Search for the top nested frame for positioning shapes when moving or creating.
  Looks for all the frames in a position and then goes in depth between the top-most and its
  children to find the target."
  ([objects position]
   (top-nested-frame objects position nil))

  ([objects position excluded]
   (assert (or (nil? excluded) (set? excluded)))

   (let [frame-ids (cond->> (all-frames-by-position objects position)
                     (some? excluded)
                     (remove excluded))

         frame-set (set frame-ids)]

     (loop [current-id (first frame-ids)]
       (let [current-shape (get objects current-id)
             child-frame-id (d/seek #(contains? frame-set %)
                                    (-> (:shapes current-shape) reverse))]
         (if (nil? child-frame-id)
           (or current-id uuid/zero)
           (recur child-frame-id)))))))

(defn top-nested-frame-ids
  "Search the top nested frame in a list of ids"
  [objects ids]

  (let [frame-ids (->> ids (filter #(cph/frame-shape? objects %)))
        frame-set (set frame-ids)]
    (loop [current-id (first frame-ids)]
      (let [current-shape (get objects current-id)
            child-frame-id (d/seek #(contains? frame-set %)
                                   (-> (:shapes current-shape) reverse))]
        (if (nil? child-frame-id)
          (or current-id uuid/zero)
          (recur child-frame-id))))))

(defn get-viewer-frames
  ([objects]
   (get-viewer-frames objects nil))

  ([objects {:keys [all-frames?]}]
   (into []
         (comp (map (d/getf objects))
               (if all-frames?
                 identity
                 (remove :hide-in-viewer)))
         (sort-z-index objects (get-frames-ids objects)))))

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

(defn clone-object
  "Gets a copy of the object and all its children, with new ids and with
  the parent-children links correctly set. Admits functions to make
  more transformations to the cloned objects and the original ones.

  Returns the cloned object, the list of all new objects (including
  the cloned one), and possibly a list of original objects modified.

  The list of objects are returned in tree traversal order, respecting
  the order of the children of each parent."

  ([object parent-id objects update-new-object]
   (clone-object object parent-id objects update-new-object (fn [object _] object) nil))

  ([object parent-id objects update-new-object update-original-object]
   (clone-object object parent-id objects update-new-object update-original-object nil))

  ([object parent-id objects update-new-object update-original-object force-id]
   (let [new-id (or force-id (uuid/next))]
     (loop [child-ids (seq (:shapes object))
            new-direct-children []
            new-children []
            updated-children []]

       (if (empty? child-ids)
         (let [new-object (cond-> object
                            :always
                            (assoc :id new-id
                                   :parent-id parent-id)

                            (some? (:shapes object))
                            (assoc :shapes (mapv :id new-direct-children)))

               new-object  (update-new-object new-object object)
               new-objects (into [new-object] new-children)

               updated-object  (update-original-object object new-object)
               updated-objects (if (identical? object updated-object)
                                 updated-children
                                 (into [updated-object] updated-children))]

           [new-object new-objects updated-objects])

         (let [child-id (first child-ids)
               child    (get objects child-id)
               _        (dm/assert! (some? child))

               [new-child new-child-objects updated-child-objects]
               (clone-object child new-id objects update-new-object update-original-object)]

           (recur
            (next child-ids)
            (into new-direct-children [new-child])
            (into new-children new-child-objects)
            (into updated-children updated-child-objects))))))))

(defn generate-shape-grid
  "Generate a sequence of positions that lays out the list of
  shapes in a grid of equal-sized rows and columns."
  [shapes start-pos gap]
  (let [shapes-bounds (map gsh/bounding-box shapes)

        grid-size   (mth/ceil (mth/sqrt (count shapes)))
        row-size    (+ (apply max (map :height shapes-bounds))
                       gap)
        column-size (+ (apply max (map :width shapes-bounds))
                       gap)

        next-pos (fn [position]
                   (let [counter (inc (:counter (meta position)))
                         row     (quot counter grid-size)
                         column  (mod counter grid-size)
                         new-pos (gpt/add start-pos
                                          (gpt/point (* column column-size)
                                                     (* row row-size)))]
                     (with-meta new-pos
                                {:counter counter})))]
    (iterate next-pos
             (with-meta start-pos
                        {:counter 0}))))
