;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.geom.shapes.tree-seq
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

(defn get-children-seq
  "Given an id returns a sequence of its children"
  [id objects]
  (->> (tree-seq
        #(d/not-empty? (dm/get-in objects [% :shapes]))
        (fn [id]
          (let [shape (get objects id)]
            (cond->> (:shapes shape)
              (and (ctl/flex-layout? shape) (ctl/reverse? shape))
              (reverse))))
        id)
       (map #(get objects %))))

;; Finds the tree root for the current id
(defn get-reflow-root
  ([id objects]
   (get-reflow-root id id objects))

  ([current last-root objects]
   (let [shape (get objects current)]
     (if (or (not ^boolean shape) (= uuid/zero current))
       last-root
       (let [parent-id (dm/get-prop shape :parent-id)
             parent    (get objects parent-id)]
         (cond
           ;; Frame found, but not layout we return the last layout found (or the id)
           (and ^boolean (cfh/frame-shape? parent)
                (not ^boolean (ctl/any-layout? parent)))
           last-root

           ;; Auto-Layout found. We continue upward but we mark this layout
           (and (ctl/any-layout? parent) (ctl/auto? parent))
           (recur parent-id parent-id objects)

           (ctl/any-layout? parent)
           parent-id

           ;; If group or boolean or other type of group we continue with the last result
           :else
           (recur parent-id last-root objects)))))))

;; Given some roots retrieves the minimum number of tree roots
(defn search-common-roots
  [ids objects]
  (let [find-root
        (fn [roots id]
          (if (= id uuid/zero)
            roots
            (let [root (get-reflow-root id objects)
                  ;; Remove the children from the current root
                  roots
                  (if ^boolean (cfh/has-children? objects root)
                    (into #{} (remove (partial cfh/is-child? objects root)) roots)
                    roots)

                  contains-parent?
                  (->> (cfh/get-parent-ids objects root)
                       (some (partial contains? roots)))]

              (cond-> roots
                (not contains-parent?)
                (conj root)))))]
    (reduce find-root #{} ids)))

(defn- push-children
  "`stack` with the children of `shape` on top, so they are taken in the
  order of `get-children-seq`."
  [stack shape]
  (let [ids (:shapes shape)]
    (if (and (ctl/flex-layout? shape) (ctl/reverse? shape))
      (into stack ids)
      (into stack (if (vector? ids) (rseq ids) (reverse ids))))))

(defn- walk-subtrees
  "Add to the transient vector `result` the shapes of the subtrees of the
  ids in `stack`, a list taken from its head, depth first and in the order
  of `get-children-seq`. Stops after adding `stop-id`."
  [objects result stack stop-id]
  (loop [stack  stack
         result result]
    (if (seq stack)
      (let [id     (first stack)
            shape  (get objects id)
            result (conj! result shape)]
        (if (= id stop-id)
          result
          (recur (push-children (rest stack) shape) result)))
      result)))

(defn resolve-tree
  "Given the ids that have changed search for layout roots to recalculate"
  [ids objects]
  (dm/assert! (or (nil? ids) (set? ids)))

  (let [child-seq
        (persistent!
         (walk-subtrees objects
                        (transient [])
                        (into () (reverse (search-common-roots ids objects)))
                        nil))]

    (if (contains? ids uuid/zero)
      (cons (get objects uuid/zero) child-seq)
      child-seq)))

(defn resolve-subtree
  "Resolves the subtree but only partially from-to the parameters: the
  shapes under `from-id` up to `to-id`, followed by the shapes under
  `to-id`."
  [from-id to-id objects]
  (as-> (transient []) result
    (walk-subtrees objects result (list from-id) to-id)
    (walk-subtrees objects result (push-children () (get objects to-id)) nil)
    (persistent! result)))
