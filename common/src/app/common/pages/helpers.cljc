;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pages.helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.components-list :as ctkl]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape.layout :as ctl]
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
  ([objects id]
   (root-frame? (get objects id)))
  ([{:keys [frame-id type]}]
   (and (= type :frame)
        (= frame-id uuid/zero))))

(defn frame-shape?
  ([objects id]
   (frame-shape? (get objects id)))
  ([{:keys [type]}]
   (= type :frame)))

(defn group-shape?
  ([objects id]
   (group-shape? (get objects id)))
  ([{:keys [type]}]
   (= type :group)))

(defn mask-shape?
  [{:keys [type masked-group?]}]
  (and (= type :group) masked-group?))

(defn bool-shape?
  [{:keys [type]}]
  (= type :bool))

(defn group-like-shape?
  [{:keys [type]}]
  (or (= :group type) (= :bool type)))

(defn text-shape?
  [{:keys [type]}]
  (= type :text))

(defn rect-shape?
  [{:keys [type]}]
  (= type :rect))

(defn image-shape?
  [{:keys [type]}]
  (= type :image))

(defn svg-raw-shape?
  [{:keys [type]}]
  (= type :svg-raw))

(defn path-shape?
  [{:keys [type]}]
  (= type :path))

(defn unframed-shape?
  "Checks if it's a non-frame shape in the top level."
  [shape]
  (and (not (frame-shape? shape))
       (= (:frame-id shape) uuid/zero)))

(defn has-children?
  ([objects id]
   (has-children? (get objects id)))
  ([shape]
   (d/not-empty? (:shapes shape))))

;; ---- ACCESSORS

(defn get-children-ids
  [objects id]
  (letfn [(get-children-ids-rec
            [id processed]
            (when (not (contains? processed id))
              (when-let [shapes (-> (get objects id) :shapes (some-> vec))]
                (into shapes (mapcat #(get-children-ids-rec % (conj processed id))) shapes))))]
    (get-children-ids-rec id #{})))

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

(defn hidden-parent?
  "Checks the parent for the hidden property"
  [objects shape-id]
  (let [parent-id (dm/get-in objects [shape-id :parent-id])]
    (cond
      (or (nil? parent-id) (nil? shape-id) (= shape-id uuid/zero) (= parent-id uuid/zero)) false
      (dm/get-in objects [parent-id :hidden]) true
      :else
      (recur objects parent-id))))

(defn get-parent-ids-with-index
  "Returns a tuple with the list of parents and a map with the position within each parent"
  [objects shape-id]
  (loop [parent-list []
         parent-indices {}
         current shape-id]
    (let [parent-id (dm/get-in objects [current :parent-id])
          parent (get objects parent-id)]
      (if (and (some? parent) (not= parent-id current))
        (let [parent-list (conj parent-list parent-id)
              parent-indices (assoc parent-indices parent-id (d/index-of (:shapes parent) current))]
          (recur parent-list parent-indices parent-id))
        [parent-list parent-indices]))))

(defn get-siblings-ids
  [objects id]
  (let [parent (get-parent objects id)]
    (into [] (->> (:shapes parent) (remove #(= % id))))))

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

(defn get-root-frame
  [objects shape-id]

  (let [frame-id
        (if (frame-shape? objects shape-id)
          shape-id
          (dm/get-in objects [shape-id :frame-id]))

        frame (get objects frame-id)]
    (cond
      (or (root? frame) (nil? frame))
      nil

      (root-frame? frame)
      frame

      :else
      (get-root-frame objects (:frame-id frame)))))

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

(defn get-prev-sibling
  [objects id]
  (let [obj (get objects id)
        pid (:parent-id obj)
        prt (get objects pid)
        shapes (:shapes prt)
        pos (d/index-of shapes id)]
    (if (= 0 pos) nil (nth shapes (dec pos)))))


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
;; COMPONENTS HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-touched-group
  [touched group]
  (conj (or touched #{}) group))

(defn touched-group?
  [shape group]
  ((or (:touched shape) #{}) group))

(defn make-container
  [page-or-component type]
  (assoc page-or-component :type type))

(defn page?
  [container]
  (= (:type container) :page))

(defn component?
  [container]
  (= (:type container) :component))

(defn get-container
  [file type id]
  (dm/assert! (map? file))
  (dm/assert! (keyword? type))
  (dm/assert! (uuid? id))

  (-> (if (= type :page)
        (ctpl/get-page file id)
        (ctkl/get-component file id))
      (assoc :type type)))

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

(defn order-by-indexed-shapes
  [objects ids]
  (->> (indexed-shapes objects)
       (sort-by first)
       (filter (comp (into #{} ids) second))
       (map second)))

(defn get-index-replacement
  "Given a collection of shapes, calculate their positions
   in the parent, find first index and return next one"
  [shapes objects]
  (->> shapes
       (order-by-indexed-shapes objects)
       first
       (get-position-on-parent objects)
       inc))

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
    (update-vals
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
        (update-vals remove-children))))

(defn is-child?
  [objects parent-id candidate-child-id]
  (loop [cur-id candidate-child-id]
    (let [cur-parent-id (dm/get-in objects [cur-id :parent-id])]
      (cond
        (= parent-id cur-parent-id)
        true

        (or (= cur-parent-id uuid/zero) (nil? cur-parent-id))
        false

        :else
        (recur cur-parent-id)))))

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
              pending-ids (rest root-children)
              processed   #{}]

         (if (contains? processed current-id)
           (recur current-val (first pending-ids) (rest pending-ids) processed)
           (let [current-shape (get objects current-id)
                 processed (conj processed current-id)
                 next-val (reducer-fn current-val current-shape)
                 next-pending-ids
                 (if (or (nil? check-children?) (check-children? current-shape))
                   (concat (or (:shapes current-shape) []) pending-ids)
                   pending-ids)]

             (if (empty? next-pending-ids)
               next-val
               (recur next-val (first next-pending-ids) (rest next-pending-ids) processed)))))))))

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

(defn comparator-layout-z-index
  [[idx-a child-a] [idx-b child-b]]
  (cond
    (> (ctl/layout-z-index child-a) (ctl/layout-z-index child-b)) 1
    (< (ctl/layout-z-index child-a) (ctl/layout-z-index child-b)) -1
    (> idx-a idx-b) 1
    (< idx-a idx-b) -1
    :else 0))

(defn sort-layout-children-z-index
  [children]
  (->> children
       (d/enumerate)
       (sort comparator-layout-z-index)
       (mapv second)))

(defn common-parent-frame
  "Search for the common frame for the selected shapes. Otherwise returns the root frame"
  [objects selected]

  (loop [frame-id (get-in objects [(first selected) :frame-id])
         frame-parents (get-parent-ids objects frame-id)
         selected (rest selected)]
    (if (empty? selected)
      frame-id

      (let [current (first selected)
            parent? (into #{} (get-parent-ids objects current))

            [frame-id frame-parents]
            (if (parent? frame-id)
              [frame-id frame-parents]

              (let [frame-id (d/seek parent? frame-parents)]
                [frame-id (get-parent-ids objects frame-id)]))]

        (recur frame-id frame-parents (rest selected))))))
