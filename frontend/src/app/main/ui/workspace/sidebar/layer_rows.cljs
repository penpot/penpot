;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.layer-rows
  "Row model of the layers tree, for rendering only the visible rows.

  Every shown shape is a `Row` holding its shown children and the number
  of rows in its subtree. Rows are reused between builds while their
  shape, position and children stay the same, so a parent shares its
  children instead of copying them, and a change only rebuilds the rows
  on the path from the changed shape to the root."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]))

(deftype Row [id shape depth index open component-child copy-parent children size])

(defn- same-rows?
  [a b]
  (and (= (count a) (count b))
       (loop [i 0]
         (cond
           (= i (count a)) true
           (identical? (nth a i) (nth b i)) (recur (inc i))
           :else false))))

(defn- rows-size
  [rows]
  (reduce (fn [total ^Row row] (+ total (.-size row))) 0 rows))

(declare build-row)

(defn- build-children
  "Rows of `shape-ids` in render order: layers show the last shape first."
  [prev-cache next-cache objects expanded shape-ids depth component-child copy-parent]
  (loop [index (dec (count shape-ids))
         rows  (transient [])]
    (if (neg? index)
      (persistent! rows)
      (let [row (build-row prev-cache next-cache objects expanded
                           (nth shape-ids index) depth index
                           component-child copy-parent)]
        (recur (dec index)
               (cond-> rows (some? row) (conj! row)))))))

(defn- build-row
  [prev-cache next-cache objects expanded id depth index component-child copy-parent]
  (when-let [shape (get objects id)]
    (let [shape-ids (get shape :shapes)
          open      (boolean (and (get expanded id) (seq shape-ids)))
          children  (if open
                      (build-children prev-cache next-cache objects expanded shape-ids (inc depth)
                                      (or component-child
                                          (ctk/instance-root? shape)
                                          (ctk/instance-head? shape))
                                      (or copy-parent
                                          (ctk/in-component-copy? shape)))
                      [])
          ^Row prev (.get prev-cache id)
          row       (if (and (some? prev)
                             (identical? shape (.-shape prev))
                             (= depth (.-depth prev))
                             (= index (.-index prev))
                             (= open (.-open prev))
                             (= component-child (.-component-child prev))
                             (= copy-parent (.-copy-parent prev))
                             (same-rows? children (.-children prev)))
                      prev
                      (Row. id shape depth index open component-child copy-parent
                            children (inc (rows-size children))))]
      (.set next-cache id row)
      row)))

(defn build-tree
  "Builds the rows of the shapes under the page root, reusing the rows in
  `prev-cache` that did not change. Returns `[root next-cache]`; the root
  is not shown, so its size counts only the rows under it. Root rows get
  depth -1, as layer items add one before using it."
  [prev-cache objects expanded]
  (let [next-cache (js/Map.)
        shape-ids  (get-in objects [uuid/zero :shapes])
        children   (build-children prev-cache next-cache objects expanded shape-ids -1 false false)]
    [(Row. uuid/zero nil -2 0 true false false children (rows-size children))
     next-cache]))

(defn get-id [^Row row] (.-id row))
(defn get-shape [^Row row] (.-shape row))
(defn get-depth [^Row row] (.-depth row))
(defn get-position [^Row row] (.-index row))
(defn open? [^Row row] (.-open row))
(defn component-child? [^Row row] (.-component-child row))
(defn copy-parent? [^Row row] (.-copy-parent row))
(defn get-size [^Row row] (.-size row))

(defn get-rows
  "Rows from flat index `start` (inclusive) to `end` (exclusive), skipping
  whole subtrees outside the range."
  [^Row root start end]
  (let [result #js []]
    (letfn [(walk [rows offset]
              (loop [i      0
                     offset offset]
                (when (and (< i (count rows)) (< offset end))
                  (let [^Row row (nth rows i)
                        size     (.-size row)]
                    (when (> (+ offset size) start)
                      (when (>= offset start)
                        (.push result row))
                      (walk (.-children row) (inc offset)))
                    (recur (inc i) (+ offset size))))))]
      (walk (.-children root) 0))
    result))

(defn get-top-row
  "The root row whose subtree holds flat index `index`, with its flat
  index, as `[row offset]`."
  [^Row root index]
  (loop [rows   (seq (.-children root))
         offset 0]
    (when-let [^Row row (first rows)]
      (let [next-offset (+ offset (.-size row))]
        (if (< index next-offset)
          [row offset]
          (recur (rest rows) next-offset))))))

(defn get-index
  "Flat index of the row of shape `id`, or nil when it is not shown."
  [^Row root objects id]
  (let [parents (set (cfh/get-parent-ids objects id))]
    (loop [rows   (seq (.-children root))
           offset 0]
      (when-let [^Row row (first rows)]
        (let [row-id (.-id row)]
          (cond
            (= id row-id)
            offset

            (contains? parents row-id)
            (recur (seq (.-children row)) (inc offset))

            :else
            (recur (rest rows) (+ offset (.-size row)))))))))
