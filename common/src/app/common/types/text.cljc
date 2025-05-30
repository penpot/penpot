;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

 (ns app.common.types.text
   (:require
    [app.common.data.macros :as dm]
    [clojure.set :as set]))

(defn- compare-text-content
  "Given two content text structures, conformed by maps and vectors,
   compare them, and returns a set with the type of differences.
   The possibilities are :text-content-text :text-content-attribute and :text-content-structure."
  [a b]
  (cond
    ;; If a and b are equal, there is no diff
    (= a b)
    #{}

    ;; If types are different, the structure is different
    (not= (type a) (type b))
    #{:text-content-structure}

    ;; If they are maps, check the keys
    (map? a)
    (let [keys (-> (set/union (set (keys a)) (set (keys b)))
                   (disj :key))] ;; We have to ignore :key because it is a draft artifact
      (reduce
       (fn [acc k]
         (let [v1 (get a k)
               v2 (get b k)]
           (cond
             ;; If the key is :children, keep digging
             (= k :children)
             (if (not= (count v1) (count v2))
               #{:text-content-structure}
               (into acc
                     (apply set/union
                            (map #(compare-text-content %1 %2) v1 v2))))

             ;; If the key is :text, and they are different, it is a text differece
             (= k :text)
             (if (not= v1 v2)
               (conj acc :text-content-text)
               acc)

             :else
             ;; If the key is not :text, and they are different, it is an attribute differece
             (if (not= v1 v2)
               (conj acc :text-content-attribute)
               acc))))
       #{}
       keys))

    :else
    #{:text-content-structure}))


(defn equal-attrs?
  "Given a text structure, and a map of attrs, check that all the internal attrs in
   paragraphs and sentences have the same attrs"
  [item attrs]
  (let [item-attrs (dissoc item :text :type :key :children)]
    (and
     (or (empty? item-attrs)
         (= attrs (dissoc item :text :type :key :children)))
     (every? #(equal-attrs? % attrs) (:children item)))))

(defn get-first-paragraph-text-attrs
  "Given a content text structure, extract it's first paragraph
   text attrs"
  [content]
  (-> content
      (dm/get-in [:children 0 :children 0])
      (dissoc :text :type :key :children)))

(defn get-diff-type
  "Given two content text structures, conformed by maps and vectors,
   compare them, and returns a set with the type of differences.
   The possibilities are :text-content-text :text-content-attribute,
   :text-content-structure and :text-content-structure-same-attrs."
  [a b]
  (let [diff-type (compare-text-content a b)]
    (if-not (contains? diff-type :text-content-structure)
      diff-type
      (let [;; get attrs of the first paragraph of the first paragraph-set
            attrs (get-first-paragraph-text-attrs a)]
        (if (and (equal-attrs? a attrs)
                 (equal-attrs? b attrs))
          #{:text-content-structure :text-content-structure-same-attrs}
          diff-type)))))

;; TODO We know that there are cases that the blocks of texts are separated
;; differently: ["one" " " "two"], ["one " "two"], ["one" " two"]
;; so this won't work for 100% of the situations. But it's good enough for now,
;; we can iterate on the solution again in the future if needed.
(defn equal-structure?
  "Given two content text structures, check that the structures are equal.
   This means that all the :children keys at any level has the same number of
   entries"
  [a b]
  (cond
    (not= (type a) (type b))
    false

    (map? a)
    (let [children-a (:children a)
          children-b (:children b)]
      (if (not= (count children-a) (count children-b))
        false
        (every? true?
                (map equal-structure? children-a children-b))))

    :else
    true))


(defn copy-text-keys
  "Given two equal content text structures, deep copy all the keys :text
   from origin to destiny"
  [origin destiny]
  (cond
    (map? origin)
    (into {}
          (for [k (keys origin) :when (not= k :key)] ;; We ignore :key because it is a draft artifact
            (cond
              (= :children k)
              [k (vec (map #(copy-text-keys %1 %2) (get origin k) (get destiny k)))]
              (= :text k)
              [k (:text origin)]
              :else
              [k (get destiny k)])))))

(defn copy-attrs-keys
  "Given a content text structure and a list of attrs, copy that
   attrs values on all the content tree"
  [content attrs]
  (into {}
        (for [[k v] content]
          (if (= :children k)
            [k (vec (map #(copy-attrs-keys %1 attrs) v))]
            [k (get attrs k v)]))))
