;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.objects-map
  "Implements a specialized map-like data structure for store an UUID =>
  OBJECT mappings. The main purpose of this data structure is be able
  to serialize it on fressian as byte-array and have the ability to
  decode each field separatelly without the need to decode the whole
  map from the byte-array.

  It works transparently, so no aditional dynamic vars are needed.  It
  only works by reference equality and the hash-code is calculated
  properly from each value."

  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as json])
   [app.common.transit :as t]
   [clojure.core :as c]
   [clojure.core.protocols :as cp])
  #?(:clj
     (:import
      clojure.lang.Murmur3
      clojure.lang.RT
      java.util.Iterator)))

#?(:clj (set! *warn-on-reflection* true))

(declare create)
(declare ^:private do-compact)

(defprotocol IObjectsMap
  (^:no-doc compact [this])
  (^:no-doc get-data [this] "retrieve internal data")
  (^:no-doc -hash-for-key [this key] "retrieve a hash for a key"))

#?(:cljs
   (deftype ObjectsMapEntry [key omap]
     c/IMapEntry
     (-key [_] key)
     (-val [_] (get omap key))

     c/IHash
     (-hash [_]
       (-hash-for-key omap key))

     c/IEquiv
     (-equiv [this other]
       (and (c/map-entry? other)
            (= (key this)
               (key other))
            (= (val this)
               (val other))))

     c/ISequential
     c/ISeqable
     (-seq [this]
       (cons key (lazy-seq (cons (c/-val this) nil))))

     c/ICounted
     (-count [_] 2)

     c/IIndexed
     (-nth [node n]
       (cond (== n 0) key
             (== n 1) (c/-val node)
             :else    (throw (js/Error. "Index out of bounds"))))

     (-nth [node n not-found]
       (cond (== n 0) key
             (== n 1) (c/-val node)
             :else    not-found))

     c/ILookup
     (-lookup [node k]
       (c/-nth node k nil))
     (-lookup [node k not-found]
       (c/-nth node k not-found))

     c/IFn
     (-invoke [node k]
       (c/-nth node k))

     (-invoke [node k not-found]
       (c/-nth node k not-found))

     c/IPrintWithWriter
     (-pr-writer [this writer opts]
       (c/pr-sequential-writer
        writer
        (fn [item w _]
          (c/-write w (pr-str item)))
        "[" ", " "]"
        opts
        this)))

   :clj
   (deftype ObjectsMapEntry [key omap]
     clojure.lang.IMapEntry
     (key [_] key)
     (getKey [_] key)

     (val [_]
       (get omap key))
     (getValue [_]
       (get omap key))

     clojure.lang.Indexed
     (nth [node n]
       (cond
         (== n 0) key
         (== n 1) (val node)
         :else    (throw (IllegalArgumentException. "Index out of bounds"))))

     (nth [node n not-found]
       (cond
         (== n 0) key
         (== n 1) (val node)
         :else    not-found))

     clojure.lang.IPersistentCollection
     (empty [_] [])
     (count [_] 2)
     (seq [this]
       (cons key (lazy-seq (cons (val this) nil))))
     (cons [this item]
       (.cons ^clojure.lang.IPersistentCollection (vec this) item))

     clojure.lang.IHashEq
     (hasheq [_]
       (-hash-for-key omap key))))

#?(:cljs
   (deftype ObjectMapIterator [iterator omap]
     Object
     (hasNext [_]
       (.hasNext ^js iterator))

     (next [_]
       (let [entry (.next iterator)]
         (ObjectsMapEntry. (key entry) omap)))

     (remove [_]
       (js/Error. "Unsupported operation")))

   :clj
   (deftype ObjectsMapIterator [^Iterator iterator omap]
     Iterator
     (hasNext [_]
       (.hasNext iterator))

     (next [_]
       (let [entry (.next iterator)]
         (ObjectsMapEntry. (key entry) omap)))))

#?(:cljs
   (deftype ObjectsMap [metadata cache
                        ^:mutable data
                        ^:mutable modified
                        ^:mutable hash]
     Object
     (toString [this]
       (pr-str* this))
     (equiv [this other]
       (c/-equiv this other))
     (keys [this]
       (c/es6-iterator (keys this)))
     (entries [this]
       (c/es6-entries-iterator (seq this)))
     (values [this]
       (es6-iterator (vals this)))
     (has [this k]
       (c/contains? this k))
     (get [this k not-found]
       (c/-lookup this k not-found))
     (forEach [this f]
       (run! (fn [[k v]] (f v k)) this))

     cp/Datafiable
     (datafy [_]
       {:data data
        :cache cache
        :modified modified
        :hash hash})

     IObjectsMap
     (compact [this]
       (when modified
         (do-compact data cache
                     (fn [data']
                       (set! (.-modified this) false)
                       (set! (.-data this) data'))))
       this)

     (get-data [this]
       (compact this)
       data)

     (-hash-for-key [this key]
       (if (c/-contains-key? cache key)
         (c/-hash (c/-lookup cache key))
         (c/-hash (c/-lookup this key))))

     c/IWithMeta
     (-with-meta [this new-meta]
       (if (identical? new-meta meta)
         this
         (ObjectsMap. new-meta
                      cache
                      data
                      modified
                      hash)))

     c/IMeta
     (-meta [_] metadata)

     c/ICloneable
     (-clone [this]
       (compact this)
       (ObjectsMap. metadata {} data false nil))

     c/IIterable
     (-iterator [this]
       (c/seq-iter this))

     c/ICollection
     (-conj [this entry]
       (cond
         (map-entry? entry)
         (c/-assoc this (c/-key entry) (c/-val entry))

         (vector? entry)
         (c/-assoc this (c/-nth entry 0) (c/-nth entry 1))

         :else
         (loop [ret this es (seq entry)]
           (if (nil? es)
             ret
             (let [e (first es)]
               (if (vector? e)
                 (recur (c/-assoc ret (c/-nth e 0) (c/-nth e 1))
                        (next es))
                 (throw (js/Error. "conj on a map takes map entries or seqables of map entries"))))))))

     c/IEmptyableCollection
     (-empty [_]
       (create))

     c/IEquiv
     (-equiv [this other]
       (equiv-map this other))

     c/IHash
     (-hash [this]
       (when-not hash
         (set! hash (hash-unordered-coll this)))
       hash)

     c/ISeqable
     (-seq [this]
       (->> (keys data)
            (map (fn [id] (new ObjectsMapEntry id this)))
            (seq)))

     c/ICounted
     (-count [_]
       (c/-count data))

     c/ILookup
     (-lookup [this k]
       (or (c/-lookup cache k)
           (if (c/-contains-key? data k)
             (let [v (c/-lookup data k)
                   v (t/decode-str v)]
               (set! (.-cache this) (c/-assoc cache k v))
               v)
             (do
               (set! (.-cache this) (assoc cache key nil))
               nil))))

     (-lookup [this k not-found]
       (if (c/-contains-key? data k)
         (c/-lookup this k)
         not-found))

     c/IAssociative
     (-assoc [_ k v]
       (ObjectsMap. metadata
                    (c/-assoc cache k v)
                    (c/-assoc data k nil)
                    true
                    nil))

     (-contains-key? [_ k]
       (c/-contains-key? data k))

     c/IFind
     (-find [this k]
       (when (c/-contains-key? data k)
         (new ObjectsMapEntry k this)))

     c/IMap
     (-dissoc [_ k]
       (ObjectsMap. metadata
                    (c/-dissoc cache k)
                    (c/-dissoc data k)
                    true
                    nil))

     c/IKVReduce
     (-kv-reduce [this f init]
       (c/-kv-reduce data
                     (fn [init k _]
                       (f init k (c/-lookup this k)))
                     init))

     c/IFn
     (-invoke [this k]
       (c/-lookup this k))
     (-invoke [this k not-found]
       (c/-lookup this k not-found))

     c/IPrintWithWriter
     (-pr-writer [this writer opts]
       (c/pr-sequential-writer
        writer
        (fn [item w _]
          (c/-write w (pr-str (c/-key item)))
          (c/-write w \space)
          (c/-write w (pr-str (c/-val item))))
        "#penpot/objects-map {" ", " "}"
        opts
        (seq this))))

   :clj
   (deftype ObjectsMap [metadata cache
                        ^:unsynchronized-mutable data
                        ^:unsynchronized-mutable modified
                        ^:unsynchronized-mutable hash]

     Object
     (hashCode [this]
       (.hasheq ^clojure.lang.IHashEq this))

     cp/Datafiable
     (datafy [_]
       {:data data
        :cache cache
        :modified modified
        :hash hash})

     IObjectsMap
     (compact [this]
       (locking this
         (when modified
           (do-compact data cache
                       (fn [data']
                         (set! (.-modified this) false)
                         (set! (.-data this) data')))))
       this)

     (get-data [this]
       (compact this)
       data)

     (-hash-for-key [this key]
       (if (contains? cache key)
         (c/hash (get cache key))
         (c/hash (get this key))))

     json/JSONWriter
     (-write [this writter options]
       (json/-write (into {} this) writter options))

     clojure.lang.IHashEq
     (hasheq [this]
       (when-not hash
         (set! hash (Murmur3/hashUnordered this)))
       hash)

     clojure.lang.Seqable
     (seq [this]
       (RT/chunkIteratorSeq (.iterator ^Iterable this)))

     java.lang.Iterable
     (iterator [this]
       (ObjectsMapIterator. (.iterator ^Iterable data) this))

     clojure.lang.IPersistentCollection
     (equiv [this other]
       (and (instance? ObjectsMap other)
            (= (count this) (count other))
            (reduce-kv (fn [_ id _]
                         (let [this-val  (get this id)
                               other-val (get other id)
                               result    (= this-val other-val)]
                           (or result
                               (reduced false))))
                       true
                       data)))

     clojure.lang.IPersistentMap
     (cons [this o]
       (if (map-entry? o)
         (assoc this (key o) (val o))
         (if (vector? o)
           (assoc this (nth o 0) (nth o 1))
           (throw (UnsupportedOperationException. "invalid arguments to cons")))))

     (empty [_]
       (create))

     (containsKey [_ key]
       (.containsKey ^clojure.lang.IPersistentMap data key))

     (entryAt [this key]
       (ObjectsMapEntry. this key))

     (valAt [this key]
       (or (get cache key)
           (locking this
             (if (contains? data key)
               (let [value (get data key)
                     value (t/decode-str value)]
                 (set! (.-cache this) (assoc cache key value))
                 value)
               (do
                 (set! (.-cache this) (assoc cache key nil))
                 nil)))))

     (valAt [this key not-found]
       (if (.containsKey ^clojure.lang.IPersistentMap data key)
         (.valAt this key)
         not-found))

     (assoc [_ key val]
       (ObjectsMap. metadata
                    (assoc cache key val)
                    (assoc data key nil)
                    true
                    nil))


     (assocEx [_ _ _]
       (throw (UnsupportedOperationException. "method not implemented")))

     (without [_ key]
       (ObjectsMap. metadata
                    (dissoc cache key)
                    (dissoc data key)
                    true
                    nil))

     clojure.lang.Counted
     (count [_]
       (count data))))

#?(:cljs (es6-iterable ObjectsMap))


(defn- do-compact
  [data cache update-fn]
  (let [new-data
        (persistent!
         (reduce-kv (fn [data id obj]
                      (if (nil? obj)
                        (assoc! data id (t/encode-str (get cache id)))
                        data))
                    (transient data)
                    data))]
    (update-fn new-data)
    nil))

(defn from-data
  [data]
  (ObjectsMap. {} {}
               data
               false
               nil))

(defn objects-map?
  [o]
  (instance? ObjectsMap o))

(defn create
  ([] (from-data {}))
  ([other]
   (cond
     (objects-map? other)
     (-> other get-data from-data)

     :else
     (throw #?(:clj (UnsupportedOperationException. "invalid arguments")
               :cljs (js/Error. "invalid arguments"))))))

(defn wrap
  [objects]
  (if (instance? ObjectsMap objects)
    objects
    (->> objects
         (into (create))
         (compact))))

#?(:clj
   (fres/add-handlers!
    {:name "penpot/objects-map/v2"
     :class ObjectsMap
     :wfn (fn [n w o]
            (fres/write-tag! w n)
            (fres/write-object! w (get-data o)))
     :rfn (fn [r]
            (-> r fres/read-object! from-data))}))

(t/add-handlers!
 {:id "penpot/objects-map/v2"
  :class ObjectsMap
  :wfn get-data
  :rfn from-data})
