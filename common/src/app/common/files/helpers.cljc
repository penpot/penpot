;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.files.helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.common :as gco]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
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
  ([shape]
   (and (some? shape)
        (= :text (dm/get-prop shape :type))))
  ([objects id]
   (text-shape? (get objects id))))

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

(defn has-layout?
  "Returns true if the provided shape has a layout assigned"
  [objects id]
  (let [shape (get objects id)]
    (boolean (and shape (:layout shape)))))

(defn group-like-shape?
  ([objects id]
   (group-like-shape? (get objects id)))
  ([shape]
   (or ^boolean (group-shape? shape)
       ^boolean (bool-shape? shape)
       ^boolean (and (svg-raw-shape? shape) (has-children? shape)))))

;; ---- ACCESSORS

(defn get-selected-type
  "Returns the type of the shape if only one, or :multiple if more
  than one"
  [objects selected]
  (if (= 1 (count selected))
    (let [shape (get objects (first selected))]
      (:type shape))
    :multiple))

(defn get-shape-type
  "Returns the type of the shape, or 'root' if it's Root Frame, always
  as string"
  [objects id]
  (let [shape (get objects id)]
    (if (root? shape)
      :root
      (dm/get-prop shape :type))))

(defn get-children-ids
  "Returns the ids of all the descendants of the shape identified
  by the id. Optionally, you can pass an ignore function to indicate
  when to ignore a descendant (and all its descendants)"
  ([objects id]
   (get-children-ids objects id {}))
  ([objects id {:keys [ignore-children-fn]
                ;;ignore-children-fn should receive a shape and return a boolean
                :or {ignore-children-fn (constantly false)}}]
   (letfn [(collect [result id processed]
             (if (contains? processed id)
               result
               (let [shapes    (into [] (remove ignore-children-fn) (dm/get-in objects [id :shapes]))
                     processed (conj processed id)]
                 (reduce #(collect %1 %2 processed)
                         (reduce conj! result shapes)
                         shapes))))]
     (persistent! (collect (transient []) id #{})))))

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
  "Returns a lazy seq of parents of the specified shape."
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

(defn get-parent-frame
  "Similar to `get-frame, but always return the parent frame. When root
  frame is provided, then itself is returned."
  [objects shape-or-id]
  (cond
    (map? shape-or-id)
    (get objects (dm/get-prop shape-or-id :frame-id))

    (= uuid/zero shape-or-id)
    (get objects uuid/zero)

    :else
    (some->> shape-or-id
             (get objects)
             (get-frame objects))))

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
    (when (and (some? pos) (pos? pos))
      (nth shapes (dec pos)))))

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
  "Selects the shape that will be the base to add the shapes over.
   Returns nil when the selection is empty or when none of the
   selected shapes is reachable in the objects tree."
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
  ([objects shape-id parent-id]
   (let [children (get-children-with-self objects shape-id)
         parents  (get-parents-with-self objects parent-id)]
     (components-nesting-loop? children parents)))
  ([children parents]
   (let [xf-get-component-id (keep :component-id)
         child-components    (into #{} xf-get-component-id children)
         parent-components   (into #{} xf-get-component-id parents)]
     (seq (set/intersection child-components parent-components)))))

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

(defn- name-seq
  "Creates a lazy, infinite sequence of names starting with `base-name`,
   followed by variants with suffixes applied. The sequence follows this pattern:
   - `base-name`
   - `(str base-name (suffix-fn 1))`
   - `(str base-name (suffix-fn 2))`
   - `(str base-name (suffix-fn 3))`, etc."
  [base-name suffix-fn]
  (cons base-name
        (map #(str/concat base-name (suffix-fn %))
             (iterate inc 1))))

(defn generate-unique-name
  "Generates a unique name by selecting the first available name from a generated sequence.
   The sequence consists of `base-name` and its variants, avoiding conflicts with `existing-names`.

   Parameters:
   - `base-name` - string used as the base for name generation.
   - `existing-names` - a collection of existing names to check for uniqueness.
   - Options:
     - `:suffix-fn` - a function that generates suffixes, given an integer (default: `get-suffix`).
     - `:immediate-suffix?` - if `true`, the base name is considered taken, and suffixing starts immediately.

   Returns:
   - A unique name not present in `existing-names`."
  [base-name existing-names & {:keys [suffix-fn immediate-suffix? suffix]}]
  (dm/assert!
   "expected a set of strings"
   (coll? existing-names))

  (dm/assert!
   "expected a string for `basename`."
   (string? base-name))
  (let [suffix-fn (if suffix-fn
                    suffix-fn
                    (if suffix
                      (fn [copy-count]
                        (str/concat "-"
                                    suffix
                                    (when (> copy-count 1)
                                      (str "-" copy-count))))
                      (fn [copy-count]
                        (str/concat " " copy-count))))

        existing-name-set (cond-> (set existing-names)
                            immediate-suffix? (conj base-name))
        names (name-seq base-name suffix-fn)]

    (->> names
         (remove #(contains? existing-name-set %))
         first)))

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
  "Clean a list of ids from circular references. Optimized fast-path for single selections."
  [objects ids]
  (if (<= (count ids) 1)
    ;; For single selection, there can't be circularity; return as ordered-set.
    (into (d/ordered-set) ids)
    (let [ids-set (if (set? ids) ids (set ids))
          parent-selected?
          (fn [id]
            ;; Stop early as soon as we find any selected parent
            (let [parents (get-parent-ids objects id)]
              (some #(contains? ids-set %) parents)))

          add-element
          (fn [result id]
            (cond-> result
              (not (parent-selected? id))
              (conj id)))]

      (reduce add-element (d/ordered-set) ids))))

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
                     (or [])
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

(def shape-ref-paths
  "Every place a shape holds a reference to another file (`:file`) or to
  a media object (`:media`) at the current data version.

  `:path` is relative to the shape. A keyword step is a map key, `:*`
  steps into every element of a vector, and `::tree` stands for the node
  and every descendant reached through `:children` (the text `:content`
  tree). The last step names the key holding the uuid.

  The shape schema declares every path, and
  `common-tests.files-ref-paths-test` fails when the schema gains a uuid
  path that is neither listed here nor classified as no reference."
  [{:path [:component-file] :kind :file}
   {:path [:metadata :id] :kind :media}
   {:path [:fills :* :fill-color-ref-file] :kind :file}
   {:path [:fills :* :fill-image :id] :kind :media}
   {:path [:strokes :* :stroke-color-ref-file] :kind :file}
   {:path [:strokes :* :stroke-image :id] :kind :media}
   {:path [:shadow :* :color :ref-file] :kind :file}
   {:path [:grids :* :params :color :ref-file] :kind :file}
   {:path [:content ::tree :fills :* :fill-color-ref-file] :kind :file}
   {:path [:content ::tree :fills :* :fill-image :id] :kind :media}
   {:path [:content ::tree :typography-ref-file] :kind :file}
   {:path [:position-data :* :fills :* :fill-color-ref-file] :kind :file}
   {:path [:position-data :* :fills :* :fill-image :id] :kind :media}])

(def legacy-shape-ref-paths
  "References in places older data versions used, in the format of
  `shape-ref-paths`. Binfile import relinks before it migrates, because
  the migrations resolve `:component-file` against the importing file's
  libraries, so it can meet any of them:

  - top-level fill and stroke colour refs, moved into `:fills` and
    `:strokes` by migration `legacy-16`, which skips text shapes;
  - a top-level `:fill-image`, once set on many shape types;
  - the shadow and grid colour `:file-id`, renamed `:ref-file` by
    migration `0015-clean-shadow-color`;
  - text nodes holding fill attrs directly, moved into `:fills` by
    migration `0006-fix-old-texts-fills`."
  [{:path [:fill-color-ref-file] :kind :file}
   {:path [:stroke-color-ref-file] :kind :file}
   {:path [:fill-image :id] :kind :media}
   {:path [:shadow :* :color :file-id] :kind :file}
   {:path [:grids :* :params :color :file-id] :kind :file}
   {:path [:content ::tree :fill-color-ref-file] :kind :file}])

(defn- relink-vector
  "Apply `f` to every element of vector `v`, rebuilding it only when an
  element changed."
  [v f lookup]
  (reduce-kv (fn [acc i x]
               (let [x' (f x lookup)]
                 (if (identical? x x') acc (assoc acc i x'))))
             v
             v))

(defn- relink-vals
  "Apply `f` to every value of map `m`, rebuilding it only when a value
  changed."
  [m f lookup]
  (reduce-kv (fn [acc k x]
               (let [x' (f x lookup)]
                 (if (identical? x x') acc (assoc acc k x'))))
             m
             m))

(defn- paths->trie
  "Merge `paths` into a nested map step -> subtrie, `::leaf` at the key
  holding the uuid."
  [paths]
  (reduce (fn [trie path]
            (assert (not= ::leaf (get-in trie (pop path)))
                    (str "ref path under another: " path))
            (assert (not (map? (get-in trie path)))
                    (str "ref path ends inside another: " path))
            (assoc-in trie path ::leaf))
          {}
          paths))

(defn- compile-trie
  "Compile a trie of ref paths into `(fn [node lookup] node')`, which
  rebuilds only what changed."
  [trie]
  (let [fs (mapv (fn [[k sub]]
                   (cond
                     (= ::leaf sub)
                     (fn [node lookup]
                       (let [v (get node k)]
                         (if (uuid? v)
                           (let [v' (lookup v)]
                             (if (= v v') node (assoc node k v')))
                           node)))

                     (= :* k)
                     (let [f (compile-trie sub)]
                       (fn [coll lookup]
                         (if (vector? coll) (relink-vector coll f lookup) coll)))

                     (= ::tree k)
                     (let [f (compile-trie sub)]
                       (fn walk [node lookup]
                         (let [node     (f node lookup)
                               children (get node :children)]
                           (if (vector? children)
                             (let [children' (relink-vector children walk lookup)]
                               (if (identical? children children')
                                 node
                                 (assoc node :children children')))
                             node))))

                     :else
                     (let [f (compile-trie sub)]
                       (fn [node lookup]
                         (let [v (get node k)]
                           (if (nil? v)
                             node
                             (let [v' (f v lookup)]
                               (if (identical? v v') node (assoc node k v')))))))))
                 trie)]
    (fn [node lookup]
      (reduce (fn [node f] (f node lookup)) node fs))))

(def ^:private relink-shape*
  (->> (concat shape-ref-paths legacy-shape-ref-paths)
       (map :path)
       (paths->trie)
       (compile-trie)))

(defn relink-shape
  "Rewrite every reference `shape-ref-paths` and `legacy-shape-ref-paths`
  name through `lookup` (uuid -> uuid, identity for unknown ids).
  Returns `shape` itself when nothing changed."
  [shape lookup]
  (relink-shape* shape lookup))

(defn relink-objects
  "`relink-shape` over every shape of an objects map. Returns `objects`
  itself when nothing changed."
  [objects lookup]
  (relink-vals objects relink-shape* lookup))

(def relink-grid-params
  "`(fn [params lookup])`: relink the colour of one grid's params, its
  `:ref-file` or the legacy `:file-id` (see `legacy-shape-ref-paths`).
  Returns `params` itself when nothing changed."
  (compile-trie (paths->trie [[:color :ref-file] [:color :file-id]])))

(defn relink-page
  "Relink the references of a page through `lookup`: its shapes and the
  colours of its default grids, also under `:options :saved-grids`, where
  they lived before migration `legacy-55` moved them."
  [page lookup]
  (let [relink-grids #(relink-vals %1 relink-grid-params %2)]
    (-> page
        (d/update-when :objects relink-objects lookup)
        (d/update-when :default-grids relink-grids lookup)
        (d/update-in-when [:options :saved-grids] relink-grids lookup))))

(defn relink-containers
  "Relink the references of every page and component of file `data`
  through `lookup`. Leaves the `:media` and `:colors` collections alone."
  [data lookup]
  (let [relink-component #(d/update-when %1 :objects relink-objects %2)]
    (-> data
        (d/update-when :pages-index relink-vals relink-page lookup)
        (d/update-when :components relink-vals relink-component lookup))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHAPES ORGANIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
