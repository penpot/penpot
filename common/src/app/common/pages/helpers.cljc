;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(declare reduce-objects)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERIC SHAPE SELECTORS AND PREDICATES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn root?
  [{:keys [id type]}]
  (and (= type :frame) (= id uuid/zero)))

(defn root-frame?
  [{:keys [frame-id type]}]
  (and (= type :frame)
       (= frame-id uuid/zero)))

(defn frame-shape?
  [{:keys [type]}]
  (= type :frame))

(defn group-shape?
  [{:keys [type]}]
  (= type :group))

(defn bool-shape?
  [{:keys [type]}]
  (= type :bool))

(defn text-shape?
  [{:keys [type]}]
  (= type :text))

(defn image-shape?
  [{:keys [type]}]
  (= type :image))

(defn svg-raw-shape?
  [{:keys [type]}]
  (= type :svg-raw))

(defn unframed-shape?
  "Checks if it's a non-frame shape in the top level."
  [shape]
  (and (not (frame-shape? shape))
       (= (:frame-id shape) uuid/zero)))

(defn get-children-ids
  [objects id]
  (if-let [shapes (-> (get objects id) :shapes (some-> vec))]
    (into shapes (mapcat #(get-children-ids objects %)) shapes)
    []))

(defn get-children
  [objects id]
  (mapv (d/getf objects) (get-children-ids objects id)))

(defn get-children-with-self
  [objects id]
  (let [lookup (d/getf objects)]
    (into [(lookup id)] (map lookup) (get-children-ids objects id))))

(defn get-parent
  "Retrieve the id of the parent for the shape-id (if exists)"
  [objects id]
  (let [lookup (d/getf objects)]
    (-> id lookup :parent-id lookup)))

(defn get-parent-id
  "Retrieve the id of the parent for the shape-id (if exists)"
  [objects id]
  (-> objects (get id) :parent-id))

(defn get-parent-ids
  "Returns a vector of parents of the specified shape."
  [objects shape-id]
  (loop [result [] id shape-id]
    (let [parent-id (dm/get-in objects [id :parent-id])]
      (if (and (some? parent-id) (not= parent-id id))
        (recur (conj result parent-id) parent-id)
        result))))

(defn get-frame
  "Get the frame that contains the shape. If the shape is already a
  frame, get itself. If no shape is provided, returns the root frame."
  ([objects]
   (get objects uuid/zero))
  ([objects shape-or-id]
   (cond
     (map? shape-or-id)
     (if (frame-shape? shape-or-id)
       shape-or-id
       (get objects (:frame-id shape-or-id)))

     (= uuid/zero shape-or-id)
     (get objects uuid/zero)

     :else
     (some->> shape-or-id
              (get objects)
              (get-frame objects)))))

(defn valid-frame-target?
  [objects parent-id shape-id]
  (let [shape (get objects shape-id)]
    (or (not (frame-shape? shape))
        (= parent-id uuid/zero))))

(defn get-position-on-parent
  [objects id]
  (let [obj (get objects id)
        pid (:parent-id obj)
        prt (get objects pid)]
    (d/index-of (:shapes prt) id)))

(defn get-immediate-children
  "Retrieve resolved shape objects that are immediate children
   of the specified shape-id"
  ([objects] (get-immediate-children objects uuid/zero))
  ([objects shape-id]
   (let [lookup (d/getf objects)]
     (->> (lookup shape-id)
          (:shapes)
          (keep lookup)))))

(declare indexed-shapes)

(defn get-base-shape
  "Selects the shape that will be the base to add the shapes over"
  [objects selected]
  (let [;; Gets the tree-index for all the shapes
        indexed-shapes (indexed-shapes objects)

        ;; Filters the selected and retrieve a list of ids
        sorted-ids (->> indexed-shapes
                        (filter (comp selected second))
                        (map second))]

    ;; The first id will be the top-most
    (get objects (first sorted-ids))))

(defn is-parent?
  "Check if `parent-candidate` is parent of `shape-id`"
  [objects shape-id parent-candidate]

  (loop [current-id shape-id]
    (cond
      (= current-id parent-candidate)
      true

      (or (nil? current-id)
          (= current-id uuid/zero)
          (= current-id (get-in objects [current-id :parent-id])))
      false

      :else
      (recur (get-in objects [current-id :parent-id])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ALGORITHMS & TRANSFORMATIONS FOR SHAPES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn walk-pages
  "Go through all pages of a file and apply a function to each one"
  ;; The function receives two parameters (page-id and page), and
  ;; returns the updated page.
  [f data]
  (update data :pages-index #(d/mapm f %)))

(defn update-object-list
  "Update multiple objects in a page at once"
  [page objects-list]
  (update page :objects
          #(into % (d/index-by :id objects-list))))

(defn insert-at-index
  [objects index ids]
  (let [[before after] (split-at index objects)
        p? (set ids)]
    (d/concat-vec []
                  (remove p? before)
                  ids
                  (remove p? after))))

(defn append-at-the-end
  [prev-ids ids]
  (reduce (fn [acc id]
            (if (some #{id} acc)
              acc
              (conj acc id)))
          (vec prev-ids)
          ids))

(defn clean-loops
  "Clean a list of ids from circular references."
  [objects ids]
  (let [parent-selected?
        (fn [id]
          (let [parents (get-parent-ids objects id)]
            (some ids parents)))

        add-element
        (fn [result id]
          (cond-> result
            (not (parent-selected? id))
            (conj id)))]

    (reduce add-element (d/ordered-set) ids)))

(defn indexed-shapes
  "Retrieves a list with the indexes for each element in the layer tree.
   This will be used for shift+selection."
  [objects]
  (letfn [(red-fn [cur-idx id]
            (let [[prev-idx _] (first cur-idx)
                  prev-idx (or prev-idx 0)
                  cur-idx (conj cur-idx [(inc prev-idx) id])]
              (rec-index cur-idx id)))
          (rec-index [cur-idx id]
            (let [object (get objects id)]
              (reduce red-fn cur-idx (reverse (:shapes object)))))]
    (into {} (rec-index '() uuid/zero))))

(defn expand-region-selection
  "Given a selection selects all the shapes between the first and last in
   an indexed manner (shift selection)"
  [objects selection]
  (let [indexed-shapes (indexed-shapes objects)
        filter-indexes (->> indexed-shapes
                            (filter (comp selection second))
                            (map first))

        from (apply min filter-indexes)
        to   (apply max filter-indexes)]
    (->> indexed-shapes
         (filter (fn [[idx _]] (and (>= idx from) (<= idx to))))
         (map second)
         (into #{}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHAPES ORGANIZATION (PATH MANAGEMENT)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn split-path
  "Decompose a string in the form 'one / two / three' into
  a vector of strings, normalizing spaces."
  [path]
  (let [xf (comp (map str/trim)
                 (remove str/empty?))]
    (->> (str/split path "/")
         (into [] xf))))

(defn join-path
  "Regenerate a path as a string, from a vector."
  [path-vec]
  (str/join " / " path-vec))

(defn parse-path-name
  "Parse a string in the form 'group / subgroup / name'.
  Retrieve the path and the name in separated values, normalizing spaces."
  [path-name]
  (let [path-name-split (split-path path-name)
        path (str/join " / " (butlast path-name-split))
        name (or (last path-name-split) "")]
    [path name]))

(defn merge-path-item
  "Put the item at the end of the path."
  [path name]
  (if-not (empty? path)
    (if-not (empty? name)
      (str path " / " name)
      path)
    name))

(defn compact-path
  "Separate last item of the path, and truncate the others if too long:
    'one'                          ->  ['' 'one' false]
    'one / two / three'            ->  ['one / two' 'three' false]
    'one / two / three / four'     ->  ['one / two / ...' 'four' true]
    'one-item-but-very-long / two' ->  ['...' 'two' true] "
  [path max-length]
  (let [path-split (split-path path)
        last-item  (last path-split)]
    (loop [other-items (seq (butlast path-split))
           other-path  ""]
      (if-let [item (first other-items)]
        (let [full-path (-> other-path
                            (merge-path-item item)
                            (merge-path-item last-item))]
          (if (> (count full-path) max-length)
            [(merge-path-item other-path "...") last-item true]
            (recur (next other-items)
                   (merge-path-item other-path item))))
        [other-path last-item false]))))

(defn compact-name
  "Append the first item of the path and the name."
  [path name]
  (let [path-split (split-path path)]
    (merge-path-item (first path-split) name)))


(defn get-frame-objects
  "Retrieves a new objects map only with the objects under frame-id (with frame-id)"
  [objects frame-id]
  (let [ids (concat [frame-id] (get-children-ids objects frame-id))]
    (select-keys objects ids)))

(defn objects-by-frame
  "Returns a map of the `objects` grouped by frame. Every value of the map has
  the same format as objects id->shape-data"
  [objects]
  ;; Implemented with transients for performance. 30~50% better
  (letfn [(process-shape [objects [id shape]]
            (let [frame-id (if (= :frame (:type shape)) id (:frame-id shape))
                  cur (-> (or (get objects frame-id) (transient {}))
                          (assoc! id shape))]
              (assoc! objects frame-id cur)))]
    (d/update-vals
     (->> objects
          (reduce process-shape (transient {}))
          (persistent!))
     persistent!)))

(defn selected-subtree
  "Given a set of shapes, returns an objects subtree with the parents
  of the selected items up to the root. Useful to calculate a partial z-index"
  [objects selected]

  (let [selected+parents
        (into selected
              (mapcat #(get-parent-ids objects %))
              selected)

        remove-children
        (fn [shape]
          (update shape :shapes #(filterv selected+parents %)))]

    (-> (select-keys objects selected+parents)
        (d/update-vals remove-children))))

(defn is-child?
  [objects parent-id candidate-child-id]
  (let [parents (get-parent-ids objects candidate-child-id)]
    (some? (d/seek #(= % parent-id) parents))))

(defn reduce-objects
  ([objects reducer-fn init-val]
   (reduce-objects objects nil reducer-fn init-val))

  ([objects check-children? reducer-fn init-val]
   (reduce-objects objects check-children? uuid/zero reducer-fn init-val))

  ([objects check-children? root-id reducer-fn init-val]
   (let [root-children (get-in objects [root-id :shapes])]
     (if (empty? root-children)
       init-val

       (loop [current-val init-val
              current-id  (first root-children)
              pending-ids (rest root-children)]


         (let [current-shape (get objects current-id)
               next-val (reducer-fn current-val current-shape)
               next-pending-ids
               (if (or (nil? check-children?) (check-children? current-shape))
                 (concat (or (:shapes current-shape) []) pending-ids)
                 pending-ids)]

           (if (empty? next-pending-ids)
             next-val
             (recur next-val (first next-pending-ids) (rest next-pending-ids)))))))))

(defn selected-with-children
  [objects selected]

  (into selected
        (mapcat #(get-children-ids objects %))
        selected))

(defn get-shape-id-root-frame
  [objects shape-id]
  (->> (get-parent-ids objects shape-id)
       (cons shape-id)
       (map (d/getf objects))
       (d/seek root-frame?)
       :id))

