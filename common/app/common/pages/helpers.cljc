;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.pages.helpers
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]))

(defn walk-pages
  "Go through all pages of a file and apply a function to each one"
  ;; The function receives two parameters (page-id and page), and
  ;; returns the updated page.
  [f data]
  (update data :pages-index #(d/mapm f %)))

(defn select-objects
  "Get a list of all objects in a container (a page or a component) that
  satisfy a condition"
  [f container]
  (filter f (vals (get container :objects))))

(defn update-object-list
  "Update multiple objects in a page at once"
  [page objects-list]
  (update page :objects
          #(into % (d/index-by :id objects-list))))

(defn get-component-shape
  "Get the parent shape linked to a component for this shape, if any"
  [shape objects]
  (if-not (:shape-ref shape)
    nil
    (if (:component-id shape)
      shape
      (if-let [parent-id (:parent-id shape)]
        (get-component-shape (get objects parent-id) objects)
        nil))))

(defn get-root-shape
  "Get the root shape linked to a component for this shape, if any"
  [shape objects]
  (if-not (:shape-ref shape)
    nil
    (if (:component-root? shape)
      shape
      (if-let [parent-id (:parent-id shape)]
        (get-root-shape (get objects parent-id) objects)
        nil))))

(defn make-container
  [page-or-component type]
  (assoc page-or-component
         :type type))

(defn page?
  [container]
  (us/assert some? (:type container))
  (= (:type container) :page))

(defn component?
  [container]
  (= (:type container) :component))

(defn get-container
  [id type local-file]
  (assert (some? type))
  (-> (if (= type :page)
        (get-in local-file [:pages-index id])
        (get-in local-file [:components id]))
      (assoc :type type)))

(defn get-shape
  [container shape-id]
  (get-in container [:objects shape-id]))

(defn get-component
  [component-id library-id local-library libraries]
  (assert (some? (:id local-library)))
  (let [file (if (= library-id (:id local-library))
               local-library
               (get-in libraries [library-id :data]))]
    (get-in file [:components component-id])))

(defn is-master-of
  [shape-master shape-inst]
  (and (:shape-ref shape-inst)
       (or (= (:shape-ref shape-inst) (:id shape-master))
           (= (:shape-ref shape-inst) (:shape-ref shape-master)))))

(defn get-component-root
  [component]
  (get-in component [:objects (:id component)]))

;; Implemented with transient for performance
(defn get-children
  "Retrieve all children ids recursively for a given object"
  [id objects]

  (loop [result (transient [])
         pending (transient [])
         next id]
    (let [children (get-in objects [next :shapes] [])
          [result pending]
          ;; Iterate through children and add them to the result
          ;; also add them in pending to check for their children
          (loop [result result
                 pending pending
                 current (first children)
                 children (rest children)]
            (if current
              (recur (conj! result current)
                     (conj! pending current)
                     (first children)
                     (rest children))
              [result pending]))]

      ;; If we have still pending, advance the iterator
      (let [length (count pending)]
        (if (pos? length)
          (let [next (get pending (dec length))]
            (recur result (pop! pending) next))
          (persistent! result))))))

(defn get-children-objects
  "Retrieve all children objects recursively for a given object"
  [id objects]
  (mapv #(get objects %) (get-children id objects)))

(defn get-object-with-children
  "Retrieve a vector with an object and all of its children"
  [id objects]
  (mapv #(get objects %) (cons id (get-children id objects))))

(defn is-shape-grouped
  "Checks if a shape is inside a group"
  [shape-id objects]
  (let [contains-shape-fn (fn [{:keys [shapes]}] ((set shapes) shape-id))
        shapes (remove #(= (:type %) :frame) (vals objects))]
    (some contains-shape-fn shapes)))

(defn get-top-frame
  [objects]
  (get objects uuid/zero))

(defn get-parent
  "Retrieve the id of the parent for the shape-id (if exists)"
  [shape-id objects]
  (let [obj (get objects shape-id)]
    (:parent-id obj)))

(defn get-parents
  [shape-id objects]
  (let [{:keys [parent-id]} (get objects shape-id)]
    (when parent-id
      (lazy-seq (cons parent-id (get-parents parent-id objects))))))

(defn generate-child-parent-index
  [objects]
  (reduce-kv
   (fn [index id obj]
     (assoc index id (:parent-id obj)))
   {} objects))

(defn clean-loops
  "Clean a list of ids from circular references."
  [objects ids]
  (loop [ids    ids
         id     (first ids)
         others (rest ids)]
    (if-not id
      ids
      (recur (cond-> ids
               (some #(contains? ids %) (get-parents id objects))
               (disj id))
             (first others)
             (rest others)))))

(defn calculate-invalid-targets
  [shape-id objects]
  (let [result #{shape-id}
        children (get-in objects [shape-id :shape])
        reduce-fn (fn [result child-id]
                    (into result (calculate-invalid-targets child-id objects)))]
    (reduce reduce-fn result children)))

(defn valid-frame-target
  [shape-id parent-id objects]
  (let [shape (get objects shape-id)]
    (or (not= (:type shape) :frame)
        (= parent-id uuid/zero))))

(defn position-on-parent
  [id objects]
  (let [obj (get objects id)
        pid (:parent-id obj)
        prt (get objects pid)]
    (d/index-of (:shapes prt) id)))

(defn insert-at-index
  [objects index ids]
  (let [[before after] (split-at index objects)
        p? (set ids)]
    (d/concat []
              (remove p? before)
              ids
              (remove p? after))))

(defn append-at-the-end
  [prev-ids ids]
  (reduce (fn [acc id]
            (if (some #{id} acc)
              acc
              (conj acc id)))
          prev-ids
          ids))

(defn select-toplevel-shapes
  ([objects] (select-toplevel-shapes objects nil))
  ([objects {:keys [include-frames? include-frame-children?]
             :or {include-frames? false
                  include-frame-children? true}}]
   (let [lookup #(get objects %)
         root   (lookup uuid/zero)
         root-children (:shapes root)

         lookup-shapes
         (fn [result id]
           (if (nil? id)
             result
             (let [obj (lookup id)
                   typ (:type obj)
                   children (:shapes obj)]

               (cond-> result
                 (or (not= :frame typ) include-frames?)
                 (d/concat [obj])

                 (and (= :frame typ) include-frame-children?)
                 (d/concat (map lookup children))))))]

     (reduce lookup-shapes [] root-children))))

(defn select-frames
  [objects]
  (let [root   (get objects uuid/zero)
        loopfn (fn loopfn [ids]
                 (let [id (first ids)
                       obj (get objects id)]
                   (cond
                     (or (nil? id) (nil? obj))
                     nil

                     (= :frame (:type obj))
                     (lazy-seq (cons obj (loopfn (rest ids))))

                     :else
                     (lazy-seq (loopfn (rest ids))))))]
    (loopfn (:shapes root))))

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

               new-object (update-new-object new-object object)

               new-objects (d/concat [new-object] new-children)

               updated-object (update-original-object object new-object)

               updated-objects (if (identical? object updated-object)
                                 updated-children
                                 (d/concat [updated-object] updated-children))]

           [new-object new-objects updated-objects])

         (let [child-id (first child-ids)
               child (get objects child-id)
               _ (us/assert some? child)

               [new-child new-child-objects updated-child-objects]
               (clone-object child new-id objects update-new-object update-original-object)]

           (recur
            (next child-ids)
            (d/concat new-direct-children [new-child])
            (d/concat new-children new-child-objects)
            (d/concat updated-children updated-child-objects))))))))


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

(defn frame-id-by-position [objects position]
  (let [frames (select-frames objects)]
    (or
     (->> frames
          (reverse)
          (d/seek #(and position (gsh/has-point? % position)))
          :id)
     uuid/zero)))

(defn set-touched-group
  [touched group]
  (conj (or touched #{}) group))

(defn touched-group?
  [shape group]
  ((or (:touched shape) #{}) group))

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
