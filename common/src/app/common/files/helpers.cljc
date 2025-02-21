;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.common :as gco]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [clojure.walk :as walk]
   [cuerdas.core :as str]))

#?(:clj (set! *warn-on-reflection* true))

(declare reduce-objects)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERIC SHAPE SELECTORS AND PREDICATES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn root?
  [shape]
  (and (= (dm/get-prop shape :type) :frame)
       (= (dm/get-prop shape :id) uuid/zero)))

(defn is-direct-child-of-root?
  ([objects id]
   (is-direct-child-of-root? (get objects id)))
  ([shape]
   (and (some? shape)
        (= (dm/get-prop shape :frame-id) uuid/zero))))

(defn root-frame?
  ([objects id]
   (if (= id uuid/zero)
     false
     (root-frame? (get objects id))))
  ([shape]
   (and (some? shape)
        (not= (dm/get-prop shape :id) uuid/zero)
        (= (dm/get-prop shape :type) :frame)
        (= (dm/get-prop shape :frame-id) uuid/zero))))

(defn frame-shape?
  ([objects id]
   (frame-shape? (get objects id)))
  ([shape]
   (and (some? shape)
        (= :frame (dm/get-prop shape :type)))))

(defn group-shape?
  ([objects id]
   (group-shape? (get objects id)))
  ([shape]
   (and (some? shape)
        (= :group (dm/get-prop shape :type)))))

(defn mask-shape?
  ([shape]
   (and ^boolean (group-shape? shape)
        ^boolean (:masked-group shape)))
  ([objects id]
   (mask-shape? (get objects id))))

(defn bool-shape?
  [shape]
  (and (some? shape)
       (= :bool (dm/get-prop shape :type))))

(defn text-shape?
  [shape]
  (and (some? shape)
       (= :text (dm/get-prop shape :type))))

(defn rect-shape?
  [shape]
  (and (some? shape)
       (= :rect (dm/get-prop shape :type))))

(defn circle-shape?
  [{:keys [type]}]
  (= type :circle))

(defn image-shape?
  [shape]
  (and (some? shape)
       (= :image (dm/get-prop shape :type))))

(defn svg-raw-shape?
  ([objects id]
   (svg-raw-shape? (get objects id)))
  ([shape]
   (and (some? shape)
        (= :svg-raw (dm/get-prop shape :type)))))

(defn path-shape?
  ([objects id]
   (path-shape? (get objects id)))
  ([shape]
   (and (some? shape)
        (= :path (dm/get-prop shape :type)))))

(defn unframed-shape?
  "Checks if it's a non-frame shape in the top level."
  [shape]
  (and (some? shape)
       (not (frame-shape? shape))
       (= (dm/get-prop shape :frame-id) uuid/zero)))

(defn has-children?
  ([objects id]
   (has-children? (get objects id)))
  ([shape]
   (d/not-empty? (:shapes shape))))

(defn group-like-shape?
  ([objects id]
   (group-like-shape? (get objects id)))
  ([shape]
   (or ^boolean (group-shape? shape)
       ^boolean (bool-shape? shape)
       ^boolean (and (svg-raw-shape? shape) (has-children? shape)))))

;; ---- ACCESSORS

(defn get-children-ids
  [objects id]
  (letfn [(get-children-ids-rec [id processed]
            (when (not (contains? processed id))
              (when-let [shapes (-> (get objects id) :shapes (some-> vec))]
                (into shapes (mapcat #(get-children-ids-rec % (conj processed id))) shapes))))]
    (get-children-ids-rec id #{})))

(defn get-children-ids-with-self
  [objects id]
  (into [id] (get-children-ids objects id)))

(defn get-children
  [objects id]
  (mapv (d/getf objects) (get-children-ids objects id)))

(defn get-children-with-self
  [objects id]
  (mapv (d/getf objects) (get-children-ids-with-self objects id)))

(defn get-child
  "Return the child of the given object with the given id (allow that the
   id may point to the object itself)."
  [objects id child-id]
  (let [shape (get objects id)]
    (if (= id child-id)
      shape
      (some #(get-child objects % child-id) (:shapes shape)))))

(defn get-parent
  "Retrieve the parent for the shape-id (if exists)"
  [objects id]
  (when-let [shape (get objects id)]
    (get objects (dm/get-prop shape :parent-id))))

(defn get-parent-id
  "Retrieve the id of the parent for the shape-id (if exists)"
  [objects id]
  (when-let [shape (get objects id)]
    (dm/get-prop shape :parent-id)))

(defn get-parent-ids
  "Returns a vector of parents of the specified shape."
  [objects shape-id]
  (loop [result []
         id shape-id]
    (let [parent-id (get-parent-id objects id)]
      (if (and (some? parent-id) (not= parent-id id))
        (recur (conj result parent-id) parent-id)
        result))))

(defn get-parent-ids-seq
  "Returns a sequence of parents of the specified shape."
  [objects shape-id]
  (let [parent-id (get-parent-id objects shape-id)]
    (when (and (some? parent-id) (not= parent-id shape-id))
      (lazy-seq (cons parent-id (get-parent-ids-seq objects parent-id))))))

(defn get-parent-ids-seq-with-self
  "Returns a sequence of parents of the specified shape, including itself."
  [objects shape-id]
  (cons shape-id (get-parent-ids-seq objects shape-id)))

(defn get-parents
  "Returns a vector of parents of the specified shape."
  [objects shape-id]
  (loop [result [] id shape-id]
    (let [parent-id (dm/get-in objects [id :parent-id])]
      (if (and (some? parent-id) (not= parent-id id))
        (recur (conj result (get objects parent-id)) parent-id)
        result))))

(defn get-parent-seq
  "Returns a vector of parents of the specified shape."
  ([objects shape-id]
   (get-parent-seq objects (get objects shape-id) shape-id))

  ([objects shape shape-id]
   (let [parent-id (dm/get-prop shape :parent-id)
         parent    (get objects parent-id)]
     (when (and (some? parent) (not= parent-id shape-id))
       (lazy-seq (cons parent (get-parent-seq objects parent parent-id)))))))

(defn get-parents-with-self
  [objects id]
  (let [lookup (d/getf objects)]
    (into [(lookup id)] (map lookup) (get-parent-ids objects id))))

(defn hidden-parent?
  "Checks the parent for the hidden property"
  [objects shape-id]
  (let [parent-id (get-parent-id objects shape-id)]
    (if (or (nil? parent-id) (nil? shape-id) (= shape-id uuid/zero) (= parent-id uuid/zero))
      false
      (if ^boolean (dm/get-in objects [parent-id :hidden])
        true
        (recur objects parent-id)))))

(defn get-parent-ids-with-index
  "Returns a tuple with the list of parents and a map with the position within each parent"
  [objects shape-id]
  (loop [parent-list []
         parent-indices {}
         current shape-id]
    (let [parent-id (get-parent-id objects current)
          parent    (get objects parent-id)]
      (if (and (some? parent) (not= parent-id current))
        (let [parent-list    (conj parent-list parent-id)
              parent-indices (assoc parent-indices parent-id (d/index-of (:shapes parent) current))]
          (recur parent-list parent-indices parent-id))
        [parent-list parent-indices]))))

(defn get-siblings-ids
  [objects id]
  (let [parent (get-parent objects id)]
    (into [] (remove #(= % id)) (:shapes parent))))

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
       (get objects (dm/get-prop shape-or-id :frame-id)))

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
  ([objects] (get-immediate-children objects uuid/zero nil))
  ([objects shape-id] (get-immediate-children objects shape-id nil))
  ([objects shape-id {:keys [remove-hidden remove-blocked] :or {remove-hidden false remove-blocked false}}]
   (let [lookup (d/getf objects)]
     (->> (lookup shape-id)
          (:shapes)
          (keep (fn [cid]
                  (when-let [child (lookup cid)]
                    (when (and (or (not remove-hidden) (not (:hidden child)))
                               (or (not remove-blocked) (not (:blocked child))))
                      child))))
          (remove gco/invalid-geometry?)))))

(declare indexed-shapes)

(defn get-base-shape
  "Selects the shape that will be the base to add the shapes over"
  [objects selected]
  (let [;; Gets the tree-index for all the shapes
        indexed-shapes (indexed-shapes objects selected)
        ;; Filters the selected and retrieve a list of ids
        sorted-ids     (map val indexed-shapes)]

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

(defn make-container
  [page-or-component type]
  (assoc page-or-component :type type))

(defn page?
  [container]
  (= (:type container) :page))

(defn component?
  [container]
  (= (:type container) :component))

(defn component-touched?
  "Check if any shape in the component is touched"
  [objects root-id]
  (->> (get-children-with-self objects root-id)
       (filter (comp seq :touched))
       seq))

(defn components-nesting-loop?
  "Check if a nesting loop would be created if the given shape is moved below the given parent"
  [objects shape-id parent-id]
  (let [xf-get-component-id (keep :component-id)

        children            (get-children-with-self objects shape-id)
        child-components    (into #{} xf-get-component-id children)

        parents             (get-parents-with-self objects parent-id)
        parent-components   (into #{} xf-get-component-id parents)]
    (seq (set/intersection child-components parent-components))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ALGORITHMS & TRANSFORMATIONS FOR SHAPES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-used-names
  "Return a set with the all unique names used in the
  elements (any entity thas has a :name)"
  [elements]
  (let [elements (if (map? elements)
                   (vals elements)
                   elements)]
    (into #{} (keep :name) elements)))

(defn- extract-numeric-suffix
  [basename]
  (if-let [[_ p1 p2] (re-find #"(.*) ([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn generate-unique-name
  "A unique name generator"
  [used basename]
  (dm/assert!
   "expected a set of strings"
   (sm/check-set-of-strings! used))

  (dm/assert!
   "expected a string for `basename`."
   (string? basename))

  (if-not (contains? used basename)
    basename
    (let [[prefix initial] (extract-numeric-suffix basename)]
      (loop [counter initial]
        (let [candidate (str prefix " " counter)]
          (if (contains? used candidate)
            (recur (inc counter))
            candidate))))))

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

(defn- indexed-shapes
  "Retrieves a vector with the indexes for each element in the layer
  tree. This will be used for shift+selection."
  [objects selected]
  (loop [index   1
         result  (transient [])
         ;; Flag to start adding elements to the index
         add?    false
         ;; Only add elements while we're in the selection, we finish when the selection is over
         pending (set selected)
         shapes  (-> objects
                     (get uuid/zero)
                     (get :shapes)
                     (rseq))]

    (let [shape-id (first shapes)]
      (if (and (d/not-empty? pending) shape-id)
        (let [shape   (get objects shape-id)
              add?    (or add? (contains? selected shape-id))
              pending (disj pending shape-id)
              result  (if add?
                        (conj! result (d/vec2 index shape-id))
                        result)]
          (if-let [children (get shape :shapes)]
            (recur (inc index)
                   result
                   add?
                   pending
                   (concat (rseq children) (rest shapes)))
            (recur (inc index)
                   result
                   add?
                   pending
                   (rest shapes))))
        (persistent! result)))))

(defn expand-region-selection
  "Given a selection selects all the shapes between the first and last in
   an indexed manner (shift selection)"
  [objects selection]
  (let [selection      (if (set? selection) selection (set selection))
        indexed-shapes (indexed-shapes objects selection)
        indexes        (map key indexed-shapes)
        from           (apply min indexes)
        to             (apply max indexes)
        xform          (comp
                        (filter (fn [[idx _]] (and (>= idx from) (<= idx to))))
                        (map val))]
    (into #{} xform indexed-shapes)))

(defn order-by-indexed-shapes
  "Retrieves a ordered vector for each element in the layer tree and
  filted by selected set"
  [objects selected]
  (let [selected (if (set? selected) selected (set selected))]
    (sequence
     (comp (filter (fn [o] (contains? selected (val o))))
           (map val))
     (indexed-shapes objects selected))))

(defn get-index-replacement
  "Given a collection of shapes, calculate their positions
   in the parent, find first index and return next one"
  [shapes objects]
  (->> shapes
       (order-by-indexed-shapes objects)
       first
       (get-position-on-parent objects)
       inc))

(defn collect-shape-media-refs
  "Collect all media refs on the provided shape. Returns a set of ids"
  [shape]
  (sequence
   (keep :id)
   ;; NOTE: because of some bug, we ended with
   ;; many shape types having the ability to
   ;; have fill-image attribute (which initially
   ;; designed for :path shapes).
   (concat [(:fill-image shape)
            (:metadata shape)]
           (map :fill-image (:fills shape))
           (map :stroke-image (:strokes shape))
           (->> (:content shape)
                (tree-seq map? :children)
                (mapcat :fills)
                (map :fill-image)))))

(def ^:private
  xform:collect-media-refs
  "A transducer for collect media-id usage across a container (page or
  component)"
  (comp
   (map :objects)
   (mapcat vals)
   (mapcat collect-shape-media-refs)))

(defn collect-used-media
  "Given a fdata (file data), returns all media references used in the
  file data"
  [data]
  (-> #{}
      (into xform:collect-media-refs (vals (:pages-index data)))
      (into xform:collect-media-refs (vals (:components data)))
      (into (keys (:media data)))))

(defn relink-refs
  "A function responsible to analyze the file data or shape for references
  and apply lookup-index on it."
  [data lookup-index]
  (letfn [(process-map-form [form]
            (cond-> form
              ;; Relink image shapes
              (and (map? (:metadata form))
                   (= :image (:type form)))
              (update-in [:metadata :id] lookup-index)

              ;; Relink paths with fill image
              (map? (:fill-image form))
              (update-in [:fill-image :id] lookup-index)

              ;; This covers old shapes and the new :fills.
              (uuid? (:fill-color-ref-file form))
              (update :fill-color-ref-file lookup-index)

              ;; This covers the old shapes and the new :strokes
              (uuid? (:stroke-color-ref-file form))
              (update :stroke-color-ref-file lookup-index)

              ;; This covers all text shapes that have typography referenced
              (uuid? (:typography-ref-file form))
              (update :typography-ref-file lookup-index)

              ;; This covers the component instance links
              (uuid? (:component-file form))
              (update :component-file lookup-index)

              ;; This covers the shadows and grids (they have directly
              ;; the :file-id prop)
              (uuid? (:file-id form))
              (update :file-id lookup-index)))

          (process-form [form]
            (if (map? form)
              (process-map-form form)
              form))]

    (walk/postwalk process-form data)))

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

(defn join-path-with-dot
  "Regenerate a path as a string, from a vector."
  [path-vec]
  (str/join "\u00A0\u2022\u00A0" path-vec))

(defn clean-path
  "Remove empty items from the path."
  [path]
  (->> (split-path path)
       (join-path)))

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

(defn merge-path-item-with-dot
  "Put the item at the end of the path."
  [path name]
  (if-not (empty? path)
    (if-not (empty? name)
      (str path "\u00A0\u2022\u00A0" name)
      path)
    name))

(defn compact-path
  "Separate last item of the path, and truncate the others if too long:
    'one'                          ->  ['' 'one' false]
    'one / two / three'            ->  ['one / two' 'three' false]
    'one / two / three / four'     ->  ['one / two / ...' 'four' true]
    'one-item-but-very-long / two' ->  ['...' 'two' true] "
  [path max-length dot?]
  (let [path-split (split-path path)
        last-item  (last path-split)
        merge-path (if dot?
                     merge-path-item-with-dot
                     merge-path-item)]
    (loop [other-items (seq (butlast path-split))
           other-path  ""]
      (if-let [item (first other-items)]
        (let [full-path (-> other-path
                            (merge-path item)
                            (merge-path last-item))]
          (if (> (count full-path) max-length)
            [(merge-path other-path "...") last-item true]
            (recur (next other-items)
                   (merge-path other-path item))))
        [other-path last-item false]))))

(defn butlast-path
  "Remove the last item of the path."
  [path]
  (let [split (split-path path)]
    (if (= 1 (count split))
      ""
      (join-path (butlast split)))))

(defn butlast-path-with-dots
  "Remove the last item of the path."
  [path]
  (let [split (split-path path)]
    (if (= 1 (count split))
      ""
      (join-path-with-dot (butlast split)))))

(defn last-path
  "Returns the last item of the path."
  [path]
  (last (split-path path)))

(defn compact-name
  "Append the first item of the path and the name."
  [path name]
  (let [path-split (split-path path)]
    (merge-path-item (first path-split) name)))


(defn split-by-last-period
  "Splits a string into two parts:
   the text before and including the last period,
   and the text after the last period."
  [s]
  (if-let [last-period (str/last-index-of s ".")]
    [(subs s 0 (inc last-period)) (subs s (inc last-period))]
    [s ""]))

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
                  cur      (-> (or (get objects frame-id)
                                   (transient {}))
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

(defn fixed-scroll?
  [shape]
  ^boolean
  (and (:fixed-scroll shape)
       (= (:parent-id shape) (:frame-id shape))
       (not= (:frame-id shape) uuid/zero)))

(defn fixed?
  [objects shape-id]
  (let [ids-to-check
        (concat
         [shape-id]
         (get-children-ids objects shape-id)
         (->> (get-parent-ids objects shape-id)
              (take-while #(and (not= % uuid/zero) (not (root-frame? objects %))))))]
    (boolean
     (->> ids-to-check
          (d/seek (fn [id] () (fixed-scroll? (get objects id))))))))
