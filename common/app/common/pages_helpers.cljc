;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.pages-helpers
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]))

(defn walk-pages
  "Go through all pages of a file and apply a function to each one"
  ;; The function receives two parameters (page-id and page), and
  ;; returns the updated page.
  [f data]
  (update data :pages-index #(d/mapm f %)))

(defn select-objects
  "Get a list of all objects in a page that satisfy a condition"
  [f page]
  (filter f (vals (get page :objects))))

(defn update-object-list
  "Update multiple objects in a page at once"
  [page objects-list]
  (update page :objects
          #(into % (d/index-by :id objects-list))))

(defn get-root-component
  "Get the root shape linked to the component for this shape, if any"
  [id objects]
  (let [obj (get objects id)]
    (if-let [component-id (:component-id obj)]
      id
      (if-let [parent-id (:parent-id obj)]
        (get-root-component parent-id obj)
        nil))))

(defn get-children
  "Retrieve all children ids recursively for a given object"
  [id objects]
  (let [shapes (vec (get-in objects [id :shapes]))]
    (if shapes
      (d/concat shapes (mapcat #(get-children % objects) shapes))
      [])))

(defn get-children-objects
  "Retrieve all children objects recursively for a given object"
  [id objects]
  (map #(get objects %) (get-children id objects)))

(defn get-object-with-children
  "Retrieve a list with an object and all of its children"
  [id objects]
  (map #(get objects %) (concat [id] (get-children id objects))))

(defn walk-children
  "Go through an object and all the children tree, and apply a
  function to each one. Return the list of changed objects."
  [id f objects]
  (let [obj (get objects id)]
    (if (nil? (:shapes obj))
      [(apply f obj)]
      (loop [children (map #(get objects %) (:shapes obj))
             updated-children []]
        (if (empty? children)
          updated-children
          (let [child (first children)]
            (recur (rest children)
                   (concat [(apply f child)] updated-children))))))))

(defn is-shape-grouped
  "Checks if a shape is inside a group"
  [shape-id objects]
  (let [contains-shape-fn (fn [{:keys [shapes]}] ((set shapes) shape-id))
        shapes (remove #(= (:type %) :frame) (vals objects))]
    (some contains-shape-fn shapes)))

(defn get-parent
  "Retrieve the id of the parent for the shape-id (if exists)"
  [shape-id objects]
  (let [obj (get objects shape-id)]
    (:parent-id obj)))

(defn get-parents
  [shape-id objects]
  (let [{:keys [parent-id] :as obj} (get objects shape-id)]
    (when parent-id
      (lazy-seq (cons parent-id (get-parents parent-id objects))))))

(defn generate-child-parent-index
  [objects]
  (reduce-kv
   (fn [index id obj]
     (assoc index id (:parent-id obj)))
   {} objects))

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

(defn select-toplevel-shapes
  ([objects] (select-toplevel-shapes objects nil))
  ([objects {:keys [include-frames?] :or {include-frames? false}}]
   (let [lookup #(get objects %)
         root   (lookup uuid/zero)
         childs (:shapes root)]
     (loop [id  (first childs)
            ids (rest childs)
            res []]
       (if (nil? id)
         res
         (let [obj (lookup id)
               typ (:type obj)]
           (recur (first ids)
                  (rest ids)
                  (if (= :frame typ)
                    (if include-frames?
                      (d/concat res [obj] (map lookup (:shapes obj)))
                      (d/concat res (map lookup (:shapes obj))))
                    (conj res obj)))))))))

(defn select-frames
  [objects]
  (let [root   (get objects uuid/zero)
        loopfn (fn loopfn [ids]
                 (let [obj (get objects (first ids))]
                   (cond
                     (nil? obj)
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
  ([object parent-id objects xf-new-object]
   (clone-object object parent-id objects xf-new-object identity))

  ([object parent-id objects xf-new-object xf-original-object]
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
                            (assoc :shapes (map :id new-direct-children)))

               new-object (xf-new-object new-object object)

               new-objects (concat [new-object] new-children)

               updated-object (xf-original-object object new-object)

               updated-objects (if (= object updated-object)
                                 updated-children
                                 (concat [updated-object] updated-children))]

           [new-object new-objects updated-objects])

         (let [child-id (first child-ids)
               child (get objects child-id)

               [new-child new-child-objects updated-child-objects]
               (clone-object child new-id objects xf-new-object xf-original-object)]

           (recur
             (next child-ids)
             (concat new-direct-children [new-child])
             (concat new-children new-child-objects)
             (concat updated-children updated-child-objects))))))))

