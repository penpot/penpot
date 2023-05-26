;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.data
  "A collection if helpers for working with data structures and other
  data resources."
  (:refer-clojure :exclude [read-string hash-map merge name update-vals
                            parse-double group-by iteration concat mapcat
                            parse-uuid max min])
  #?(:cljs
     (:require-macros [app.common.data]))

  (:require
   #?(:cljs [cljs.reader :as r]
      :clj [clojure.edn :as r])
   #?(:cljs [cljs.core :as c]
      :clj [clojure.core :as c])
   [app.common.math :as mth]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [linked.map :as lkm]
   [linked.set :as lks])
  #?(:clj
     (:import
      linked.set.LinkedSet
      linked.map.LinkedMap
      java.lang.AutoCloseable)))

(def boolean-or-nil?
  (some-fn nil? boolean?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Structures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ordered-set
  ([] lks/empty-linked-set)
  ([a] (conj lks/empty-linked-set a))
  ([a & xs] (apply conj lks/empty-linked-set a xs)))

(defn ordered-map
  ([] lkm/empty-linked-map)
  ([a] (conj lkm/empty-linked-map a))
  ([a & xs] (apply conj lkm/empty-linked-map a xs)))

(defn ordered-set?
  [o]
  #?(:cljs (instance? lks/LinkedSet o)
     :clj (instance? LinkedSet o)))

(defn ordered-map?
  [o]
  #?(:cljs (instance? lkm/LinkedMap o)
     :clj (instance? LinkedMap o)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Structures Access & Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn not-empty?
  [coll]
  (boolean (seq coll)))

(defn editable-collection?
  [m]
  #?(:clj (instance? clojure.lang.IEditableCollection m)
     :cljs (implements? c/IEditableCollection m)))

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

(defn concat-all
  "A totally lazy implementation of concat with different call
  signature. It works like a flatten with a single level of nesting."
  [colls]
  (lazy-seq
   (let [c (seq colls)
         o (first c)
         r (rest c)]
     (if-let [o (seq o)]
       (cons (first o) (concat-all (cons (rest o) r)))
       (some-> (seq r) concat-all)))))

(defn mapcat
  "A fully lazy version of mapcat."
  ([f] (c/mapcat f))
  ([f & colls]
   (concat-all (apply map f colls))))

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

(defn group-by
  ([kf coll] (group-by kf identity [] coll))
  ([kf vf coll] (group-by kf vf [] coll))
  ([kf vf iv coll]
   (let [conj (fnil conj iv)]
     (reduce (fn [result item]
               (update result (kf item) conj (vf item)))
             {}
             coll))))

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
  ([kf coll] (index-by kf identity coll))
  ([kf vf coll]
   (persistent!
    (reduce #(assoc! %1 (kf %2) (vf %2)) (transient {}) coll))))

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

(defn vec-without-nils
  [coll]
  (into [] (remove nil?) coll))

(defn without-nils
  "Given a map, return a map removing key-value
  pairs when value is `nil`."
  ([] (remove (comp nil? val)))
  ([data]
   (into {} (without-nils) data)))

(defn without-qualified
  ([]
   (remove (comp qualified-keyword? key)))
  ([data]
   (into {} (without-qualified) data)))

(defn without-keys
  "Return a map without the keys provided
  in the `keys` parameter."
  [data keys]
  (persistent!
   (reduce dissoc!
           (if (editable-collection? data)
             (transient data)
             (transient {}))
           keys)))

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

(defn without-obj
  "Clear collection from specified obj and without nil values."
  [coll o]
  (into [] (filter #(not= % o)) coll))

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
  (into {} (filter pred) coll))

(defn removem
  "Remove values of a map that satisfy a predicate"
  [pred coll]
  (into {} (remove pred) coll))

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
                    (c/concat acc other))))))

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

(defn txt-merge
  "Text attrs specific merge function."
  [obj attrs]
  (reduce-kv (fn [obj k v]
               (if (nil? v)
                 (dissoc obj k)
                 (assoc obj k v)))
             obj
             attrs))

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

(defn with-next
  "Given a collection will return a new collection where each element
  is paired with the next item in the collection
  (with-next (range 5)) => [[0 1] [1 2] [2 3] [3 4] [4 nil]]"
  [coll]
  (map vector
       coll
       (c/concat (rest coll) [nil])))

(defn with-prev
  "Given a collection will return a new collection where each element
  is paired with the previous item in the collection
  (with-prev (range 5)) => [[0 nil] [1 0] [2 1] [3 2] [4 3]]"
  [coll]
  (map vector
       coll
       (c/cons nil coll)))

(defn with-prev-next
  "Given a collection will return a new collection where every item is paired
  with the previous and the next item of a collection
  (with-prev-next (range 5)) => [[0 nil 1] [1 0 2] [2 1 3] [3 2 4] [4 3 nil]]"
  [coll]
  (map vector
       coll
       (c/cons nil coll)
       (c/concat (rest coll) [nil])))

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

(defn iteration
  "Creates a totally lazy seqable via repeated calls to step, a
  function of some (continuation token) 'k'. The first call to step
  will be passed initk, returning 'ret'. If (somef ret) is true, (vf
  ret) will be included in the iteration, else iteration will
  terminate and vf/kf will not be called. If (kf ret) is non-nil it
  will be passed to the next step call, else iteration will terminate.

  This can be used e.g. to consume APIs that return paginated or batched data.

   step - (possibly impure) fn of 'k' -> 'ret'
   :somef - fn of 'ret' -> logical true/false, default 'some?'
   :vf - fn of 'ret' -> 'v', a value produced by the iteration, default 'identity'
   :kf - fn of 'ret' -> 'next-k' or nil (signaling 'do not continue'), default 'identity'
   :initk - the first value passed to step, default 'nil'

  It is presumed that step with non-initk is
  unreproducible/non-idempotent. If step with initk is unreproducible
  it is on the consumer to not consume twice."
  [& args]
  (->> (apply c/iteration args)
       (concat-all)))

(defn insert-at-index
  "Insert a list of elements at the given index of a previous list.
  Replace all existing elems."
  [elems index new-elems]
  (let [[before after] (split-at index elems)
        p? (set new-elems)]
    (concat-vec []
                (remove p? before)
                new-elems
                (remove p? after))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Parsing / Conversion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nan?
  [v]
  #?(:cljs (js/isNaN v)
     :clj  (not= v v)))

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

(defn parse-uuid
  [v]
  (try
    (c/parse-uuid v)
    (catch #?(:clj Throwable :cljs :default) _
      nil)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  ([default]
   (map #(nilv % default)))
  ([v default]
   (if (some? v) v default)))

(defn num?
  "Checks if a value `val` is a number but not an Infinite or NaN"
  ([a]
   (mth/finite? a))
  ([a b]
   (and ^boolean (mth/finite? a)
        ^boolean (mth/finite? b)))
  ([a b c]
   (and ^boolean (mth/finite? a)
        ^boolean (mth/finite? b)
        ^boolean (mth/finite? c)))
  ([a b c d]
   (and ^boolean (mth/finite? a)
        ^boolean (mth/finite? b)
        ^boolean (mth/finite? c)
        ^boolean (mth/finite? d)))
  ([a b c d & others]
   (and ^boolean (mth/finite? a)
        ^boolean (mth/finite? b)
        ^boolean (mth/finite? c)
        ^boolean (mth/finite? d)
        ^boolean (every? mth/finite? others))))

(defn max
  ([a] a)
  ([a b] (mth/max a b))
  ([a b c] (mth/max a b c))
  ([a b c d] (mth/max a b c d))
  ([a b c d e] (mth/max a b c d e))
  ([a b c d e f] (mth/max a b c d e f))
  ([a b c d e f & other]
   (reduce max (mth/max a b c d e f) other)))

(defn min
  ([a] a)
  ([a b] (mth/min a b))
  ([a b c] (mth/min a b c))
  ([a b c d] (mth/min a b c d))
  ([a b c d e] (mth/min a b c d e))
  ([a b c d e f] (mth/min a b c d e f))
  ([a b c d e f & other]
   (reduce min (mth/min a b c d e f) other)))

(defn check-num
  "Function that checks if a number is nil or nan. Will return 0 when not
  valid and the number otherwise."
  ([v] (mth/finite v 0))
  ([v default] (mth/finite v default)))

(defn any-key? [element & rest]
  (some #(contains? element %) rest))

(defn name
  "Improved version of name that won't fail if the input is not a keyword"
  ([maybe-keyword] (name maybe-keyword nil))
  ([maybe-keyword default-value]
   (cond
     (keyword? maybe-keyword)
     (c/name maybe-keyword)

     (string? maybe-keyword)
     maybe-keyword

     (nil? maybe-keyword) default-value

     :else
     (or default-value
         (str maybe-keyword)))))

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

   (if (> (count basename) 1000)
     ;; We skip generating names for long strings. If the name is too long the regex can hang
     basename
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
               candidate))))))))

(defn kebab-keys [m]
  (->> m
       (deep-mapm
        (fn [[k v]]
          (if (or (keyword? k) (string? k))
            [(keyword (str/kebab (name k))) v]
            [k v])))))

(defn toggle-selection
  ([set value]
   (toggle-selection set value false))

  ([set value toggle?]
   (if-not toggle?
     (conj (ordered-set) value)
     (if (contains? set value)
       (disj set value)
       (conj set value)))))

(defn lazy-map
  "Creates a map with lazy values given the generator function that receives as argument
  the key for the value to be generated"
  [keys generator-fn]
  (into {}
        (map (fn [key]
               [key (delay (generator-fn key))]))
        keys))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def stylize-re1 (re-pattern "(?u)(\\p{Lu}+[\\p{Ll}\\u0027\\p{Ps}\\p{Pe}]*)"))
(def stylize-re2 (re-pattern "(?u)[^\\p{L}\\p{N}\\u0027\\p{Ps}\\p{Pe}\\?!]+"))

(defn- stylize-split
  [s]
  (some-> s
          (name)
          (str/replace stylize-re1 "-$1")
          (str/split stylize-re2)
          (seq)))

(defn- stylize-join
  ([coll every-fn join-with]
   (when (seq coll)
     (str/join join-with (map every-fn coll))))
  ([[fst & rst] first-fn rest-fn join-with]
   (when (string? fst)
     (str/join join-with (cons (first-fn fst) (map rest-fn rst))))))

(defn stylize
  ([s every-fn join-with]
   (stylize s every-fn every-fn join-with))
  ([s first-fn rest-fn join-with]
    (let [remove-empty #(seq (remove empty? %))]
      (some-> (stylize-split s)
              (remove-empty)
              (stylize-join first-fn rest-fn join-with)))))

(defn camel
  "Output will be: lowerUpperUpperNoSpaces
  accepts strings and keywords"
  [s]
  (stylize s str/lower str/capital ""))

(defn kebab
  "Output will be: lower-cased-and-separated-with-dashes
  accepts strings and keywords"
  [s]
  (stylize s str/lower "-"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Util protocols
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ICloseable
  :extend-via-metadata true
  (close! [_] "Close the resource."))

#?(:clj
   (extend-protocol ICloseable
     AutoCloseable
     (close! [this] (.close this))))
