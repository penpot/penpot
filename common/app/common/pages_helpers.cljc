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

(defn get-children
  "Retrieve all children ids recursively for a given object"
  [id objects]
  (let [shapes (vec (get-in objects [id :shapes]))]
    (if shapes
      (d/concat shapes (mapcat #(get-children % objects) shapes))
      [])))

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

