;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.objects-map
  (:require
   ;; [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.util.fressian :as fres]
   [clojure.core :as c])
  (:import
   clojure.lang.Counted
   clojure.lang.IHashEq
   clojure.lang.IMapEntry
   clojure.lang.IObj
   clojure.lang.IPersistentCollection
   clojure.lang.IPersistentMap
   clojure.lang.Murmur3
   clojure.lang.RT
   clojure.lang.Seqable
   java.nio.ByteBuffer
   java.util.Iterator
   java.util.UUID))

(set! *warn-on-reflection* true)

(def ^:dynamic *lazy* true)

(def RECORD-SIZE (+ 16 8))

(declare create)

(defprotocol IObjectsMap
  (-initialize! [_])
  (-compact! [_])
  (-get-byte-array [_])
  (-get-key-hash [_ key])
  (-force-modified! [_]))

(deftype ObjectsMapEntry [^UUID key cmap]
  IMapEntry
  (key [_] key)
  (getKey [_] key)

  (val [_]
    (get cmap key))
  (getValue [_]
    (get cmap key))

  IHashEq
  (hasheq [_]
    (-get-key-hash cmap key)))

(deftype ObjectsMapIterator [^Iterator iterator cmap]
  Iterator
  (hasNext [_]
    (.hasNext iterator))

  (next [_]
    (let [entry (.next iterator)]
      (ObjectsMapEntry. (key entry) cmap))))

(deftype ObjectsMap [^:unsynchronized-mutable metadata
                     ^:unsynchronized-mutable hash
                     ^:unsynchronized-mutable positions
                     ^:unsynchronized-mutable cache
                     ^:unsynchronized-mutable blob
                     ^:unsynchronized-mutable header
                     ^:unsynchronized-mutable content
                     ^:unsynchronized-mutable initialized?
                     ^:unsynchronized-mutable modified?]

  IHashEq
  (hasheq [this]
    (when-not hash
      (set! hash (Murmur3/hashUnordered this)))
    hash)

  Object
  (hashCode [this]
    (.hasheq ^IHashEq this))

  IObjectsMap
  (-initialize! [_]
    (when-not initialized?
      ;; (l/trace :fn "-initialize!" :blob blob ::l/async false)
      (let [hsize      (.getInt ^ByteBuffer blob 0)
            header'    (.slice ^ByteBuffer blob 4 hsize)
            content'   (.slice ^ByteBuffer blob
                                (int (+ 4 hsize))
                                (int (- (.remaining ^ByteBuffer blob)
                                        (+ 4 hsize))))

            nitems     (long (/ (.remaining ^ByteBuffer header') RECORD-SIZE))
            positions' (reduce (fn [positions i]
                                 (let [hb   (.slice ^ByteBuffer header'
                                                    (int (* i RECORD-SIZE))
                                                    (int RECORD-SIZE))
                                       msb  (.getLong ^ByteBuffer hb)
                                       lsb  (.getLong ^ByteBuffer hb)
                                       size (.getInt ^ByteBuffer hb)
                                       pos  (.getInt ^ByteBuffer hb)
                                       key  (uuid/custom msb lsb)
                                       val  [size pos]]
                                   (assoc! positions key val)))
                               (transient {})
                               (range nitems))]
        (set! positions (persistent! positions'))
        (if *lazy*
          (set! cache {})
          (loop [cache'  (transient {})
                 entries (seq positions)]
            (if-let [[key [size pos]] (first entries)]
              (let [tmp (byte-array (- size 4))]
                (.get ^ByteBuffer content' (int (+ pos 4)) ^bytes tmp (int 0) (int (- size 4)))
                ;; (l/trace :fn "-initialize!" :step "preload" :key key :size size :pos pos ::l/async false)
                (recur (assoc! cache' key (fres/decode tmp))
                       (rest entries)))

              (set! cache (persistent! cache')))))

        (set! header header')
        (set! content content')
        (set! initialized? true))))

  (-force-modified! [this]
    (set! modified? true)
    (doseq [key (keys positions)]
      (let [val (get this key)]
        (set! positions (assoc positions key nil))
        (set! cache (assoc cache key val)))))

  (-compact! [_]
    (when modified?
      (let [[total-items total-size new-items new-hashes]
            (loop [entries     (seq positions)
                   total-size  0
                   total-items 0
                   new-items   {}
                   new-hashes  {}]
              (if-let [[key [size _ :as entry]] (first entries)]
                (if (nil? entry)
                  (let [oval (get cache key)
                        bval (fres/encode oval)
                        size (+ (alength ^bytes bval) 4)]
                    (recur (rest entries)
                           (+ total-size size)
                           (inc total-items)
                           (assoc new-items key bval)
                           (assoc new-hashes key (c/hash oval))))
                  (recur (rest entries)
                         (long (+ total-size size))
                         (inc total-items)
                         new-items
                         new-hashes))
                [total-items total-size new-items new-hashes]))

            hsize    (* total-items RECORD-SIZE)
            blob'    (doto (ByteBuffer/allocate (+ hsize total-size 4))
                       (.putInt 0 (int hsize)))
            header'  (.slice ^ByteBuffer blob' 4 (int hsize))
            content' (.slice ^ByteBuffer blob' (int (+ 4 hsize)) (int total-size))
            rbuf     (ByteBuffer/allocate RECORD-SIZE)

            positions'
            (loop [position  0
                   entries   (seq positions)
                   positions {}]
              (if-let [[key [size prev-pos :as entry]] (first entries)]
                (do
                  (doto ^ByteBuffer rbuf
                    (.clear)
                    (.putLong ^long (uuid/get-word-high key))
                    (.putLong ^long (uuid/get-word-low key)))

                  (if (nil? entry)
                    (let [bval (get new-items key)
                          hval (get new-hashes key)
                          size (+ (alength ^bytes bval) 4)]

                      ;; (l/trace :fn "-compact!" :cache "miss" :key key :size size :pos position ::l/async false)

                      (.putInt ^ByteBuffer rbuf (int size))
                      (.putInt ^ByteBuffer rbuf (int position))
                      (.rewind ^ByteBuffer rbuf)

                      (.put ^ByteBuffer header' ^ByteBuffer rbuf)
                      (.putInt ^ByteBuffer content' (int hval))
                      (.put ^ByteBuffer content' ^bytes bval)
                      (recur (+ position size)
                             (rest entries)
                             (assoc positions key [size position])))

                    (let [cbuf (.slice ^ByteBuffer content (int prev-pos) (int size))]
                      (.putInt ^ByteBuffer rbuf (int size))
                      (.putInt ^ByteBuffer rbuf (int position))
                      (.rewind ^ByteBuffer rbuf)

                      ;; (l/trace :fn "-compact!" :cache "hit" :key key :size size :pos position ::l/async false)
                      (.put ^ByteBuffer header' ^ByteBuffer rbuf)
                      (.put ^ByteBuffer content' ^ByteBuffer cbuf)
                      (recur (long (+ position size))
                             (rest entries)
                             (assoc positions key [size position])))))

                positions))]

        (.rewind ^ByteBuffer header')
        (.rewind ^ByteBuffer content')
        (.rewind ^ByteBuffer blob')

        ;; (l/trace :fn "-compact!" :step "end" ::l/async false)

        (set! positions positions')
        (set! modified? false)
        (set! blob blob')
        (set! header header')
        (set! content content'))))

  (-get-byte-array [this]
    ;; (l/trace :fn "-get-byte-array" :this (.getHashCode this) :blob blob ::l/async false)
    (-compact! this)
    (.array ^ByteBuffer blob))

  (-get-key-hash [this key]
    (-initialize! this)
    (if (contains? cache key)
      (c/hash (get cache key))
      (let [[_ pos] (get positions key)]
        (.getInt ^ByteBuffer content (int pos)))))

  clojure.lang.IDeref
  (deref [_]
    {:positions positions
     :cache cache
     :blob blob
     :header header
     :content content
     :initialized? initialized?
     :modified? modified?})

  Cloneable
  (clone [_]
    (if initialized?
      (ObjectsMap. metadata hash positions cache blob header content initialized? modified?)
      (ObjectsMap. metadata nil nil nil blob nil nil false false)))

  IObj
  (meta [_] metadata)
  (withMeta [this meta]
    (set! metadata meta)
    this)

  Seqable
  (seq [this]
    (-initialize! this)
    (RT/chunkIteratorSeq (.iterator ^Iterable this)))

  IPersistentCollection
  (equiv [_ _]
    (throw (UnsupportedOperationException. "not implemented")))

  IPersistentMap
  (cons [this o]
    (-initialize! this)
    (if (map-entry? o)
      (do
        ;; (l/trace :fn "cons" :key (key o))
        (assoc this (key o) (val o)))
      (if (vector? o)
        (do
          ;; (l/trace :fn "cons" :key (nth o 0))
          (assoc this (nth o 0) (nth o 1)))
        (throw (UnsupportedOperationException. "invalid arguments to cons")))))

  (empty [_]
    (create))

  (containsKey [this key]
    (-initialize! this)
    (contains? positions key))

  (entryAt [this key]
    (-initialize! this)
    (ObjectsMapEntry. this key))

  (valAt [this key]
    (-initialize! this)
    ;; (strace/print-stack-trace (ex-info "" {}))
    (if (contains? cache key)
      (do
        ;; (l/trace :fn "valAt" :key key :cache "hit")
        (get cache key))
      (do
        (if (contains? positions key)
          (let [[size pos] (get positions key)
                tmp        (byte-array (- size 4))]
            (.get ^ByteBuffer content (int (+ pos 4)) ^bytes tmp (int 0) (int (- size 4)))
            ;; (l/trace :fn "valAt" :key key :cache "miss" :size size :pos pos)

            (let [val (fres/decode tmp)]
              (set! cache (assoc cache key val))
              val))
          (do
            ;; (l/trace :fn "valAt" :key key :cache "miss" :val nil)
            (set! cache (assoc cache key nil))
            nil)))))

  (valAt [this key not-found]
    (-initialize! this)
    (if (.containsKey ^IPersistentMap positions key)
      (.valAt this key)
      not-found))

  (assoc [this key val]
    (-initialize! this)
    ;; (l/trace :fn "assoc" :key key ::l/async false)
    (ObjectsMap. metadata
                 nil
                 (assoc positions key nil)
                 (assoc cache key val)
                 blob
                 header
                 content
                 initialized?
                 true))

  (assocEx [_ _ _]
    (throw (UnsupportedOperationException. "method not implemented")))

  (without [this key]
    (-initialize! this)
    ;; (l/trace :fn "without" :key key ::l/async false)
    (ObjectsMap. metadata
                 nil
                 (dissoc positions key)
                 (dissoc cache key)
                 blob
                 header
                 content
                 initialized?
                 true))

  Counted
  (count [_]
    (count positions))

  Iterable
  (iterator [this]
    (-initialize! this)
    (ObjectsMapIterator. (.iterator ^Iterable positions) this))
  )

(defn create
  ([]
   (let [buf (ByteBuffer/allocate 4)]
     (.putInt ^ByteBuffer buf 0 0)
     (create buf)))
  ([buf]
   (cond
     (bytes? buf)
     (create (ByteBuffer/wrap ^bytes buf))

     (instance? ByteBuffer buf)
     (ObjectsMap. {} nil {} {} buf nil nil false false)

     :else
     (throw (UnsupportedOperationException. "invalid arguments")))))

(defn wrap
  [objects]
  (if (instance? ObjectsMap objects)
    objects
    (into (create) objects)))

(fres/add-handlers!
 {:name "penpot/experimental/objects-map"
  :class ObjectsMap
  :wfn (fn [n w o]
         (fres/write-tag! w n)
         (fres/write-bytes! w (-get-byte-array o)))
  :rfn (fn [r]
         (-> r fres/read-object! create))})

(t/add-handlers!
 {:id "map"
  :class ObjectsMap
  :wfn #(into {} %)})
