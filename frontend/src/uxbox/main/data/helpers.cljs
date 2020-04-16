;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.helpers
  (:require [uxbox.common.data :as d]))

(defn get-children
  "Retrieve all children ids recursively for a given shape"
  [shape-id objects]
  (let [shapes (get-in objects [shape-id :shapes])]
    (if shapes
      (d/concat shapes (mapcat #(get-children % objects) shapes))
      [])))

(defn is-shape-grouped
  "Checks if a shape is inside a group"
  [shape-id objects]
  (let [contains-shape-fn
        (fn [{:keys [shapes]}] ((set shapes) shape-id))

        shapes (remove #(= (:type %) :frame) (vals objects))]
    (some contains-shape-fn shapes)))

(defn get-parent
  "Retrieve the id of the parent for the shape-id (if exists)"
  [shape-id objects]
  (let [check-parenthood
        (fn [shape] (when (and (:shapes shape)
                               ((set (:shapes shape)) shape-id))
                      (:id shape)))]
    (some check-parenthood (vals objects))))

(defn calculate-child-parent-map
  [objects]
  (let [red-fn
        (fn [acc {:keys [id shapes]}]
          ;; Insert every pair shape -> parent into accumulated value
          (into acc (map #(vector % id) (or shapes []))))]
    (reduce red-fn {} (vals objects))))

(defn get-all-parents
  [shape-id objects]
  (let [child->parent (calculate-child-parent-map objects)
        rec-fn (fn [cur result]
                 (if-let [parent (child->parent cur)]
                   (recur parent (conj result parent))
                   (vec (reverse result))))]
    (rec-fn shape-id [])))

(defn replace-shapes
  "Replace inside shapes the value `to-replace-id` for the value in
  items keeping the same order.  `to-replace-id` can be a set, a
  sequable or a single value. Any of these will be changed into a set
  to make the replacement"
  [shape to-replace-id items]
  (let [should-replace
        (cond
          (set? to-replace-id) to-replace-id
          (seqable? to-replace-id) (set to-replace-id)
          :else #{to-replace-id})

        ;; This function replaces the first ocurrence of the set `should-replace` for the
        ;; value in `items`. Next elements that match are removed but not replaced again
        ;; so for example:
        ;; should-replace = #{2 3 5}
        ;; (replace-fn [ 1 2 3 4 5] ["a" "b"] [])
        ;;  => [ 1 "a" "b" 4 ]
        replace-fn
        (fn [to-replace acc shapes]
          (if (empty? shapes)
            acc
            (let [cur (first shapes)
                  rest (subvec shapes 1)]
              (if (should-replace cur)
                (recur [] (into acc to-replace) rest)
                (recur to-replace (conj acc cur) rest)))))

        replace-shapes (partial replace-fn (if (seqable? items) items [items]) [])]

    (update shape :shapes replace-shapes)))
