;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.helpers
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.spec :as us]
   [app.common.spec.page :as spec.page]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERIC SHAPE SELECTORS AND PREDICATES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^boolean root-frame?
  [{:keys [id type]}]
  (and (= type :frame)
       (= id uuid/zero)))

(defn ^boolean frame-shape?
  [{:keys [type]}]
  (= type :frame))

(defn ^boolean group-shape?
  [{:keys [type]}]
  (= type :group))

(defn ^boolean text-shape?
  [{:keys [type]}]
  (= type :text))

(defn ^boolean unframed-shape?
  "Checks if it's a non-frame shape in the top level."
  [shape]
  (and (not (frame-shape? shape))
       (= (:frame-id shape) uuid/zero)))

(defn get-shape
  [container shape-id]
  (us/assert ::spec.page/container container)
  (us/assert ::us/uuid shape-id)
  (-> container
      (get :objects)
      (get shape-id)))

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
    (if-let [parent-id (->> id (get objects) :parent-id)]
      (recur (conj result parent-id) parent-id)
      result)))

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

(defn get-frames
  "Retrieves all frame objects as vector. It is not implemented in
  function of `get-immediate-children` for performance reasons. This
  function is executed in the render hot path."
  [objects]
  (let [lookup (d/getf objects)
        xform  (comp (keep lookup)
                     (filter frame-shape?))]
    (->> (:shapes (lookup uuid/zero))
         (into [] xform))))

(defn frame-id-by-position
  [objects position]
  (let [frames (get-frames objects)]
    (or
     (->> frames
          (reverse)
          (d/seek #(and position (gsh/has-point? % position)))
          :id)
     uuid/zero)))

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

  (loop [current (get objects parent-candidate)
         done #{}
         pending (:shapes current)]

    (cond
      (contains? done (:id current))
      (recur (get objects (first pending))
             done
             (rest pending))

      (empty? pending) false
      (and current (contains? (set (:shapes current)) shape-id)) true

      :else
      (recur (get objects (first pending))
             (conj done (:id current))
             (concat (rest pending) (:shapes current))))))

(defn get-index-in-parent
  "Retrieves the index in the parent"
  [objects shape-id]
  (let [shape (get objects shape-id)
        parent (get objects (:parent-id shape))
        [parent-idx _] (d/seek (fn [[_idx child-id]] (= child-id shape-id))
                               (d/enumerate (:shapes parent)))]
    parent-idx))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENTS HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-touched-group
  [touched group]
  (conj (or touched #{}) group))

(defn touched-group?
  [shape group]
  ((or (:touched shape) #{}) group))

(defn get-component
  "Retrieve a component from libraries, if no library-id is provided, we
  iterate over all libraries and find the component on it."
  ([libraries component-id]
   (some #(-> % :data :components (get component-id)) (vals libraries)))
  ([libraries library-id component-id]
   (get-in libraries [library-id :data :components component-id])))

(defn ^boolean is-main-of?
  [shape-main shape-inst]
  (and (:shape-ref shape-inst)
       (or (= (:shape-ref shape-inst) (:id shape-main))
           (= (:shape-ref shape-inst) (:shape-ref shape-main)))))

(defn get-component-root
  [component]
  (get-in component [:objects (:id component)]))

(defn get-component-shape
  "Get the parent shape linked to a component for this shape, if any"
  [objects shape]
  (if-not (:shape-ref shape)
    nil
    (if (:component-id shape)
      shape
      (if-let [parent-id (:parent-id shape)]
        (get-component-shape objects (get objects parent-id))
        nil))))

(defn get-root-shape
  "Get the root shape linked to a component for this shape, if any."
  [objects shape]

  (cond
    (some? (:component-root? shape))
    shape

    (some? (:shape-ref shape))
    (recur objects (get objects (:parent-id shape)))))

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
  (us/assert map? file)
  (us/assert keyword? type)
  (us/assert uuid? id)

  (-> (if (= type :page)
        (get-in file [:pages-index id])
        (get-in file [:components id]))
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

(defn clone-object
  "Gets a copy of the object and all its children, with new ids
  and with the parent-children links correctly set. Admits functions
  to make more transformations to the cloned objects and the
  original ones.

  Returns the cloned object, the list of all new objects (including
  the cloned one), and possibly a list of original objects modified."

  ([object parent-id objects update-new-object]
   (clone-object object parent-id objects update-new-object identity))

  ([object parent-id objects update-new-object update-original-object]
   (let [new-id (uuid/next)]
     (loop [child-ids (seq (:shapes object))
            new-direct-children []
            new-children []
            updated-children []]

       (if (empty? child-ids)
         (let [new-object (cond-> object
                            true
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
               child (get objects child-id)
               _ (us/assert some? child)

               [new-child new-child-objects updated-child-objects]
               (clone-object child new-id objects update-new-object update-original-object)]

           (recur
            (next child-ids)
            (into new-direct-children [new-child])
            (into new-children new-child-objects)
            (into updated-children updated-child-objects))))))))

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
