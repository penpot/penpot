;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.path-names
  (:require
   [cuerdas.core :as str]))

"Functions to manipulate entity names that represent groups with paths,
 e.g. 'Group / Subgroup / Name'.

Some naming conventions:
 - Path string: the full string with groups and name, e.g. 'Group / Subgroup / Name'.
 - Path: a vector of strings with the full path, e.g. ['Group' 'Subgroup' 'Name'].
 - Group string: the group part of the path string, e.g. 'Group / Subgroup'.
 - Group: a vector of strings with the group part of the path, e.g. ['Group' 'Subgroup'].
 - Name: the final name part of the path, e.g. 'Name'."

(defn split-path
  "Decompose a path string in the form 'one / two / three' into a vector
   of strings, trimming spaces (e.g. ['one' 'two' 'three'])."
  [path-str & {:keys [separator] :or {separator "/"}}]
  (let [xf (comp (map str/trim)
                 (remove str/empty?))]
    (->> (str/split path-str separator)
         (into [] xf))))

(defn join-path
  "Regenerate a path as a string, from a vector.
   (e.g. ['one' 'two' 'three'] -> 'one / two / three')"
  [path & {:keys [separator with-spaces?] :or {separator "/" with-spaces? true}}]
  (if with-spaces?
    (str/join (str " " separator " ") path)
    (str/join separator path)))

(defn split-group-name
  "Parse a path string. Retrieve the group and the name in separated values,
   normalizing spaces (e.g. 'group / subgroup / name' -> ['group / subgroup' 'name'])."
  [path-str & {:keys [separator with-spaces?] :or {separator "/" with-spaces? true}}]
  (let [path      (split-path path-str :separator separator)
        group-str (join-path (butlast path) :separator separator :with-spaces? with-spaces?)
        name      (or (last path) "")]
    [group-str name]))

(defn join-path-with-dot
  "Regenerate a path as a string, from a vector."
  [path-vec]
  (str/join "\u00A0\u2022\u00A0" path-vec))

(defn clean-path
  "Remove empty items from the path."
  [path]
  (->> (split-path path)
       (join-path)))

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
  [path separator]
  (let [split (split-path path :separator separator)]
    (if (= 1 (count split))
      ""
      (join-path (butlast split) :separator separator))))

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

(defn inside-path? [child parent]
  (let [child-path  (split-path child)
        parent-path (split-path parent)]
    (and (<= (count parent-path) (count child-path))
         (= parent-path (take (count parent-path) child-path)))))

(defn split-by-last-period
  "Splits a string into two parts:
   the text before and including the last period,
   and the text after the last period."
  [s]
  (if-let [last-period (str/last-index-of s ".")]
    [(subs s 0 (inc last-period)) (subs s (inc last-period))]
    [s ""]))

;; Tree building functions --------------------------------------------------

"Build tree structure from flat list of paths"

"`build-tree-root` is the main function to build the tree."

"Receives a list of segments with 'name' properties representing paths,
   and a separator string."
"E.g segments = [{... :name 'one/two/three'} {... :name 'one/two/four'} {... :name 'one/five'}]"

"Transforms into a tree structure like:
   [{:name 'one'
     :path 'one'
     :depth 0
     :leaf nil
     :children-fn (fn [] [{:name 'two'
                           :path 'one.two'
                           :depth 1
                           :leaf nil
                           :children-fn (fn [] [{... :name 'three'} {... :name 'four'}])}
                          {:name 'five'
                           :path 'one.five'
                           :depth 1
                           :leaf {... :name 'five'}
                           ...}])}]"

(defn- sort-by-children
  "Sorts segments so that those with children come first."
  [segments separator]
  (sort-by (fn [segment]
             (let [path (split-path (:name segment) :separator separator)
                   path-length (count path)]
               (if (= path-length 1)
                 1
                 0)))
           segments))

(defn- group-by-first-segment
  "Groups segments by their first path segment and update segment name."
  [segments separator]
  (reduce (fn [acc segment]
            (let [[first-segment & remaining-segments] (split-path (:name segment) :separator separator)
                  rest-path (when (seq remaining-segments) (join-path remaining-segments :separator separator :with-spaces? false))]
              (update acc first-segment (fnil conj [])
                      (if rest-path
                        (assoc segment :name rest-path)
                        segment))))
          {}
          segments))

(defn- sort-and-group-segments
  "Sorts elements and groups them by their first path segment."
  [segments separator]
  (let [sorted  (sort-by-children segments separator)
        grouped (group-by-first-segment sorted separator)]
    grouped))

(defn- build-tree-node
  "Builds a single tree node with lazy children."
  [segment-name remaining-segments separator parent-path depth]
  (let [current-path (if parent-path
                       (str parent-path "." segment-name)
                       segment-name)

        is-leaf? (and (seq remaining-segments)
                      (every? (fn [segment]
                                (let [remaining-segment-name (first (split-path (:name segment) :separator separator))]
                                  (= segment-name remaining-segment-name)))
                              remaining-segments))

        leaf-segment (when is-leaf? (first remaining-segments))
        node {:name segment-name
              :path current-path
              :depth depth
              :leaf leaf-segment
              :children-fn (when-not is-leaf?
                             (fn []
                               (let [grouped-elements (sort-and-group-segments remaining-segments separator)]
                                 (mapv (fn [[child-segment-name remaining-child-segments]]
                                         (build-tree-node child-segment-name remaining-child-segments separator current-path (inc depth)))
                                       grouped-elements))))}]
    node))

(defn build-tree-root
  "Builds the root level of the tree."
  [segments separator]
  (let [grouped-elements (sort-and-group-segments segments separator)]
    (mapv (fn [[segment-name remaining-segments]]
            (build-tree-node segment-name remaining-segments separator nil 0))
          grouped-elements)))
