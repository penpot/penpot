;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.data
  "Data manipulation and query helper functions."
  (:refer-clojure :exclude [read-string hash-map merge name parse-double group-by iteration])
  #?(:cljs
     (:require-macros [app.common.data]))
  (:require
   [app.common.math :as mth]
   [clojure.set :as set]
   [cuerdas.core :as str]
   #?(:cljs [cljs.reader :as r]
      :clj [clojure.edn :as r])
   #?(:cljs [cljs.core :as core]
      :clj [clojure.core :as core])
   [linked.set :as lks])

  #?(:clj
     (:import linked.set.LinkedSet)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Structures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ordered-set
  ([] lks/empty-linked-set)
  ([a] (conj lks/empty-linked-set a))
  ([a & xs] (apply conj lks/empty-linked-set a xs)))

(defn ordered-set?
  [o]
  #?(:cljs (instance? lks/LinkedSet o)
     :clj (instance? LinkedSet o)))

#?(:clj
   (defmethod print-method clojure.lang.PersistentQueue [q, w]
     ;; Overload the printer for queues so they look like fish
     (print-method '<- w)
     (print-method (seq q) w)
     (print-method '-< w)))

(defn queue
  ([] #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs #queue []))
  ([a] (into (queue) [a]))
  ([a & more] (into (queue) (cons a more))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Structures Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn deep-merge
  ([a b]
   (if (map? a)
     (merge-with deep-merge a b)
     b))
  ([a b & rest]
   (reduce deep-merge a (cons b rest))))

(defn dissoc-in
  [m [k & ks]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn- transient-concat
  [c1 colls]
  (loop [result (transient c1)
         colls  colls]
    (if colls
      (recur (reduce conj! result (first colls))
             (next colls))
      (persistent! result))))

(defn concat-set
  ([] #{})
  ([c1]
   (if (set? c1) c1 (into #{} c1)))
  ([c1 & more]
   (if (set? c1)
     (transient-concat c1 more)
     (transient-concat #{} (cons c1 more)))))

(defn concat-vec
  ([] [])
  ([c1]
   (if (vector? c1) c1 (into [] c1)))
  ([c1 & more]
   (if (vector? c1)
     (transient-concat c1 more)
     (transient-concat [] (cons c1 more)))))

(defn preconj
  [coll elem]
  (assert (vector? coll))
  (into [elem] coll))

(defn enumerate
  ([items] (enumerate items 0))
  ([items start]
   (loop [idx   start
          items items
          res   (transient [])]
     (if (empty? items)
       (persistent! res)
       (recur (inc idx)
              (rest items)
              (conj! res [idx (first items)]))))))

(defn seek
  ([pred coll]
   (seek pred coll nil))
  ([pred coll not-found]
   (reduce (fn [_ x]
             (if (pred x)
               (reduced x)
               not-found))
           not-found coll)))

(defn index-by
  "Return a indexed map of the collection keyed by the result of
  executing the getter over each element of the collection."
  [getter coll]
  (persistent!
   (reduce #(assoc! %1 (getter %2) %2) (transient {}) coll)))

(defn index-of-pred
  [coll pred]
  (loop [c    (first coll)
         coll (rest coll)
         index 0]
    (if (nil? c)
      nil
      (if (pred c)
        index
        (recur (first coll)
               (rest coll)
               (inc index))))))

(defn index-of
  [coll v]
  (index-of-pred coll #(= % v)))

(defn replace-by-id
  ([value]
   (map (fn [item]
          (if (= (:id item) (:id value))
            value
            item))))
  ([coll value]
   (sequence (replace-by-id value) coll)))

(defn without-nils
  "Given a map, return a map removing key-value
  pairs when value is `nil`."
  [data]
  (into {} (remove (comp nil? second)) data))

(defn without-qualified
  [data]
  (into {} (remove (comp qualified-keyword? first)) data))

(defn without-keys
  "Return a map without the keys provided
  in the `keys` parameter."
  [data keys]
  (when (map? data)
    (persistent!
     (reduce #(dissoc! %1 %2) (transient data) keys))))

(defn remove-at-index
  "Takes a vector and returns a vector with an element in the
  specified index removed."
  [v index]
  ;; The subvec function returns a SubVector type that is an vector
  ;; but does not have transient impl, because of this, we need to
  ;; pass an explicit vector as first argument.
  (concat-vec []
              (subvec v 0 index)
              (subvec v (inc index))))

(defn zip [col1 col2]
  (map vector col1 col2))

(defn mapm
  "Map over the values of a map"
  ([mfn]
   (map (fn [[key val]] [key (mfn key val)])))
  ([mfn coll]
   (into {} (mapm mfn) coll)))

(defn removev
  "Returns a vector of the items in coll for which (fn item) returns logical false"
  [fn coll]
  (filterv (comp not fn) coll))

(defn filterm
  "Filter values of a map that satisfy a predicate"
  [pred coll]
  (into {} (filter pred coll)))

(defn removem
  "Remove values of a map that satisfy a predicate"
  [pred coll]
  (into {} (remove pred coll)))

(defn map-perm
  "Maps a function to each pair of values that can be combined inside the
  function without repetition.

  Optional parameters:
  `pred?`   A predicate that if not satisfied won't process the pair
  `target?` A collection that will be used as seed to be stored

  Example:
  (map-perm vector [1 2 3 4]) => [[1 2] [1 3] [1 4] [2 3] [2 4] [3 4]]"
  ([mfn coll]
   (map-perm mfn (constantly true) [] coll))
  ([mfn pred? coll]
   (map-perm mfn pred? [] coll))
  ([mfn pred? target coll]
   (loop [result (transient target)
          current (first coll)
          coll (rest coll)]
     (if (not current)
       (persistent! result)
       (let [result
             (loop [result result
                    other (first coll)
                    coll (rest coll)]
               (if (not other)
                 result
                 (recur (cond-> result
                          (pred? current other)
                          (conj! (mfn current other)))
                        (first coll)
                        (rest coll))))]
         (recur result
                (first coll)
                (rest coll)))))))

(defn join
  "Returns a new collection with the cartesian product of both collections.
  For example:
    (join [1 2 3] [:a :b]) => ([1 :a] [1 :b] [2 :a] [2 :b] [3 :a] [3 :b])
  You can pass a function to merge the items. By default is `vector`:
    (join [1 2 3] [1 10 100] *) => (1 10 100 2 20 200 3 30 300)"
  ([col1 col2] (join col1 col2 vector []))
  ([col1 col2 join-fn] (join col1 col2 join-fn []))
  ([col1 col2 join-fn acc]
   (cond
     (empty? col1) acc
     (empty? col2) acc
     :else (recur (rest col1) col2 join-fn
                  (let [other (mapv (partial join-fn (first col1)) col2)]
                    (concat acc other))))))

(def sentinel
  #?(:clj (Object.)
     :cljs (js/Object.)))

(defn getf
  "Returns a function to access a map"
  [coll]
  (partial get coll))

(defn update-in-when
  [m key-seq f & args]
  (let [found (get-in m key-seq sentinel)]
    (if-not (identical? sentinel found)
      (assoc-in m key-seq (apply f found args))
      m)))

(defn update-when
  [m key f & args]
  (let [found (get m key sentinel)]
    (if-not (identical? sentinel found)
      (assoc m key (apply f found args))
      m)))

(defn assoc-in-when
  [m key-seq v]
  (let [found (get-in m key-seq sentinel)]
    (if-not (identical? sentinel found)
      (assoc-in m key-seq v)
      m)))

(defn assoc-when
  [m key v]
  (let [found (get m key sentinel)]
    (if-not (identical? sentinel found)
      (assoc m key v)
      m)))

(defn domap
  "A side effect map version."
  ([f]
   (map (fn [x] (f x) x)))
  ([f coll]
   (map (fn [x] (f x) x) coll)))

(defn merge
  [& maps]
  (reduce conj (or (first maps) {}) (rest maps)))

(defn distinct-xf
  [f]
  (fn [rf]
    (let [seen (volatile! #{})]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result input]
         (let [input* (f input)]
           (if (contains? @seen input*)
             result
             (do (vswap! seen conj input*)
                 (rf result input)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Parsing / Conversion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nan?
  [v]
  (not= v v))

(defn- impl-parse-integer
  [v]
  #?(:cljs (js/parseInt v 10)
     :clj (try
            (Integer/parseInt v)
            (catch Throwable _
              nil))))

(defn- impl-parse-double
  [v]
  #?(:cljs (js/parseFloat v)
     :clj (try
            (Double/parseDouble v)
            (catch Throwable _
              nil))))

(defn parse-integer
  ([v]
   (parse-integer v nil))
  ([v default]
   (let [v (impl-parse-integer v)]
     (if (or (nil? v) (nan? v))
       default
       v))))

(defn parse-double
  ([v]
   (parse-double v nil))
  ([v default]
   (let [v (impl-parse-double v)]
     (if (or (nil? v) (nan? v))
       default
       v))))

(defn num-string? [v]
  ;; https://stackoverflow.com/questions/175739/built-in-way-in-javascript-to-check-if-a-string-is-a-valid-number
  #?(:cljs (and (string? v)
               (not (js/isNaN v))
               (not (js/isNaN (parse-double v))))

     :clj  (not= (parse-double v :nan) :nan)))

(defn read-string
  [v]
  (r/read-string v))

(defn coalesce-str
  [val default]
  (if (or (nil? val) (nan? val))
    default
    (str val)))

(defn coalesce
  [val default]
  (or val default))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Parsing / Conversion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn nilf
  "Returns a new function that if you pass nil as any argument will
  return nil"
  [f]
  (fn [& args]
    (if (some nil? args)
      nil
      (apply f args))))

(defn nilv
  "Returns a default value if the given value is nil"
  [v default]
  (if (some? v) v default))

(defn num?
  "Checks if a value `val` is a number but not an Infinite or NaN"
  ([val]
   (and (number? val)
        (mth/finite? val)
        (not (mth/nan? val))))

  ([val & vals]
   (and (num? val)
        (->> vals (every? num?)))))

(defn check-num
  "Function that checks if a number is nil or nan. Will return 0 when not
  valid and the number otherwise."
  ([v]
   (check-num v 0))
  ([v default]
   (if (num? v) v default)))

(defn any-key? [element & rest]
  (some #(contains? element %) rest))

(defn name
  "Improved version of name that won't fail if the input is not a keyword"
  ([maybe-keyword] (name maybe-keyword nil))
  ([maybe-keyword default-value]
   (cond
     (keyword? maybe-keyword)
     (core/name maybe-keyword)

     (string? maybe-keyword)
     maybe-keyword

     (nil? maybe-keyword) default-value

     :else
     (or default-value
         (str maybe-keyword)))))

(defn with-next
  "Given a collection will return a new collection where each element
  is paired with the next item in the collection
  (with-next (range 5)) => [[0 1] [1 2] [2 3] [3 4] [4 nil]]"
  [coll]
  (map vector
       coll
       (concat (rest coll) [nil])))

(defn with-prev
  "Given a collection will return a new collection where each element
  is paired with the previous item in the collection
  (with-prev (range 5)) => [[0 nil] [1 0] [2 1] [3 2] [4 3]]"
  [coll]
  (map vector
       coll
       (concat [nil] coll)))

(defn with-prev-next
  "Given a collection will return a new collection where every item is paired
  with the previous and the next item of a collection
  (with-prev-next (range 5)) => [[0 nil 1] [1 0 2] [2 1 3] [3 2 4] [4 3 nil]]"
  [coll]
  (map vector
       coll
       (concat [nil] coll)
       (concat (rest coll) [nil])))

(defn prefix-keyword
  "Given a keyword and a prefix will return a new keyword with the prefix attached
  (prefix-keyword \"prefix\" :test) => :prefix-test"
  [prefix kw]
  (let [prefix (if (keyword? prefix) (name prefix) prefix)
        kw     (if (keyword? kw) (name kw) kw)]
    (keyword (str prefix kw))))

(defn tap
  "Similar to the tap in rxjs but for plain collections"
  [f coll]
  (f coll)
  coll)

(defn tap-r
  "Same but with args reversed, for -> threads"
  [coll f]
  (f coll)
  coll)

(defn map-diff
  "Given two maps returns the diff of its attributes in a map where
  the keys will be the attributes that change and the values the previous
  and current value. For attributes which value is a map this will be recursive.

  For example:
  (map-diff {:a 1 :b 2 :c { :foo 1 :var 2}
            {:a 2      :c { :foo 10 } :d 10)

     => { :a [1 2]
          :b [2 nil]
          :c { :foo [1 10]
               :var [2 nil]}
          :d [nil 10] }

  If both maps are identical the result will be an empty map."
  [m1 m2]

  (let [m1ks (set (keys m1))
        m2ks (set (keys m2))
        keys (set/union m1ks m2ks)

        diff-attr
        (fn [diff key]

          (let [v1 (get m1 key)
                v2 (get m2 key)]
            (cond
              (= v1 v2)
              diff

              (and (map? v1) (map? v2))
              (assoc diff key (map-diff v1 v2))

              :else
              (assoc diff key [(get m1 key) (get m2 key)]))))]

    (->> keys
         (reduce diff-attr {}))))

(defn- extract-numeric-suffix
  [basename]
  (if-let [[_ p1 p2] (re-find #"(.*)-([0-9]+)$" basename)]
    [p1 (+ 1 (parse-integer p2))]
    [basename 1]))

(defn unique-name
  "A unique name generator"
  ([basename used]
   (unique-name basename used false))

  ([basename used prefix-first?]
   (assert (string? basename))
   (assert (set? used))

   (let [[prefix initial] (extract-numeric-suffix basename)]
     (if (and (not prefix-first?)
              (not (contains? used basename)))
       basename
       (loop [counter initial]
         (let [candidate (if (and (= 1 counter) prefix-first?)
                           (str prefix)
                           (str prefix "-" counter))]
           (if (contains? used candidate)
             (recur (inc counter))
             candidate)))))))

(defn deep-mapm
  "Applies a map function to an associative map and recurses over its children
  when it's a vector or a map"
  [mfn m]
  (let [do-map
        (fn [entry]
          (let [[k v] (mfn entry)]
            (cond
              (or (vector? v) (map? v))
              [k (deep-mapm mfn v)]

              :else
              (mfn [k v]))))]
    (cond
      (map? m)
      (into {} (map do-map) m)

      (vector? m)
      (into [] (map (partial deep-mapm mfn)) m)

      :else
      m)))

(defn not-empty?
  [coll]
  (boolean (seq coll)))

(defn kebab-keys [m]
  (->> m
       (deep-mapm
        (fn [[k v]]
          (if (or (keyword? k) (string? k))
            [(keyword (str/kebab (name k))) v]
            [k v])))))


(defn group-by
  ([kf coll] (group-by kf identity [] coll))
  ([kf vf coll] (group-by kf vf [] coll))
  ([kf vf iv coll]
   (let [conj (fnil conj iv)]
     (reduce (fn [result item]
               (update result (kf item) conj (vf item)))
             {}
             coll))))

;; TEMPORAL COPY of clojure-1.11 iteration function, should be
;; replaced with the builtin on when stable version is released.

#?(:clj
   (defn iteration
     "Creates a seqable/reducible via repeated calls to step,
     a function of some (continuation token) 'k'. The first call to step
     will be passed initk, returning 'ret'. Iff (somef ret) is true,
     (vf ret) will be included in the iteration, else iteration will
     terminate and vf/kf will not be called. If (kf ret) is non-nil it
     will be passed to the next step call, else iteration will terminate.
     This can be used e.g. to consume APIs that return paginated or batched data.
      step - (possibly impure) fn of 'k' -> 'ret'
      :somef - fn of 'ret' -> logical true/false, default 'some?'
      :vf - fn of 'ret' -> 'v', a value produced by the iteration, default 'identity'
      :kf - fn of 'ret' -> 'next-k' or nil (signaling 'do not continue'), default 'identity'
      :initk - the first value passed to step, default 'nil'
     It is presumed that step with non-initk is unreproducible/non-idempotent.
     If step with initk is unreproducible it is on the consumer to not consume twice."
     {:added "1.11"}
     [step & {:keys [somef vf kf initk]
              :or {vf identity
                   kf identity
                   somef some?
                   initk nil}}]
     (reify
       clojure.lang.Seqable
       (seq [_]
         ((fn next [ret]
            (when (somef ret)
              (cons (vf ret)
                    (when-some [k (kf ret)]
                      (lazy-seq (next (step k)))))))
          (step initk)))
       clojure.lang.IReduceInit
       (reduce [_ rf init]
         (loop [acc init
                ret (step initk)]
           (if (somef ret)
             (let [acc (rf acc (vf ret))]
               (if (reduced? acc)
                 @acc
                 (if-some [k (kf ret)]
                   (recur acc (step k))
                   acc)))
             acc))))))
