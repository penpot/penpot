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
