;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.data
  "A collection of data transformation utils."
  (:require [cljs.reader :as r]
            [cuerdas.core :as str]))

;; TODO: partially move to uxbox.common.helpers

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data structure manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn index-by
  "Return a indexed map of the collection
  keyed by the result of executing the getter
  over each element of the collection."
  [getter coll]
  (persistent!
   (reduce #(assoc! %1 (getter %2) %2) (transient {}) coll)))

(def index-by-id #(index-by :id %))

(defn remove-nil-vals
  "Given a map, return a map removing key-value
  pairs when value is `nil`."
  [data]
  (into {} (remove (comp nil? second) data)))

(defn without-keys
  "Return a map without the keys provided
  in the `keys` parameter."
  [data keys]
  (persistent!
   (reduce #(dissoc! %1 %2) (transient data) keys)))

(defn dissoc-in
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn index-of
  "Return the first index when appears the `v` value
  in the `coll` collection."
  [coll v]
  (first (keep-indexed (fn [idx x]
                         (when (= v x) idx))
                       coll)))

(defn replace-by-id
  ([value]
   (map (fn [item]
          (if (= (:id item) (:id value))
            value
            item))))
  ([coll value]
   (sequence (replace-by-id value) coll)))

(defn deep-merge
  "Like merge, but merges maps recursively."
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn conj-or-disj
  "Given a set, and an element remove that element from set
  if it exists or add it if it does not exists."
  [s v]
  (if (contains? s v)
    (disj s v)
    (conj s v)))

(defn enumerate
  ([items] (enumerate items 0))
  ([items start]
   (loop [idx start
          items items
          res []]
     (if (empty? items)
       res
       (recur (inc idx)
              (rest items)
              (conj res [idx (first items)]))))))

(defn concatv
  [& colls]
  (loop [colls colls
         result []]
    (if (seq colls)
      (recur (rest colls) (reduce conj result (first colls)))
      result)))

(defn seek
  ([pred coll]
   (seek pred coll nil))
  ([pred coll not-found]
   (reduce (fn [_ x]
             (if (pred x)
               (reduced x)
               not-found))
           not-found coll)))

;; --- String utils

(def +uuid-re+
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn uuid-str?
  [v]
  (and (string? v)
       (re-seq +uuid-re+ v)))

;; --- Interop

(defn jscoll->vec
  "Convert array like js object into vector."
  [v]
  (-> (clj->js [])
      (.-slice)
      (.call v)
      (js->clj)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Numbers Parsing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nan?
  [v]
  (js/isNaN v))

(defn read-string
  [v]
  (r/read-string v))

(defn parse-int
  ([v]
   (parse-int v nil))
  ([v default]
   (let [v (js/parseInt v 10)]
     (if (or (not v) (nan? v))
       default
       v))))

(defn parse-float
  ([v]
   (parse-float v nil))
  ([v default]
   (let [v (js/parseFloat v)]
     (if (or (not v) (nan? v))
       default
       v))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Other
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn classnames
  [& params]
  {:pre [(even? (count params))]}
  (str/join " " (reduce (fn [acc [k v]]
                          (if (true? v)
                            (conj acc (name k))
                            acc))
                        []
                        (partition 2 params))))

;; (defn normalize-attrs
;;   [m]
;;   (letfn [(transform [[k v]]
;;             (cond
;;               (or (= k :class) (= k :class-name))
;;               ["className" v]

;;               (or (keyword? k) (string? k))
;;               [(str/camel (name k)) v]

;;               :else
;;               [k v]))
;;           (walker [x]
;;             (if (map? x)
;;               (into {} (map tf) x)
;;               x))]
;;     (walk/postwalk walker m)))

(defn normalize-props
  [props]
  (clj->js props :keyword-fn (fn [key]
                               (if (or (= key :class) (= key :class-name))
                                 "className"
                                 (str/camel (name key))))))


;; (defn coalesce
;;   [^number v ^number n]
;;   (if (.-toFixed v)
;;     (js/parseFloat (.toFixed v n))
;;     0))



;; (defmacro mirror-map [& fields]
;;   (let [keys# (map #(keyword (name %)) fields)
;;         vals# fields]
;;     (apply hash-map (interleave keys# vals#))))

;; (defmacro some->'
;;   [x & forms]
;;   `(let [x# (p/then' ~x (fn [v#]
;;                           (when (nil? v#)
;;                             (throw (ex-info "internal" {::some-interrupt true})))
;;                           v#))]
;;      (-> (-> x# ~@forms)
;;          (p/catch' (fn [e#]
;;                      (if (::some-interrupt (ex-data e#))
;;                        nil
;;                        (throw e#)))))))
