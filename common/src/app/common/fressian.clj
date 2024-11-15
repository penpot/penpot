;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.fressian
  (:require
   [app.common.data :as d]
   [clojure.data.fressian :as fres])
  (:import
   clojure.lang.Ratio
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.time.Instant
   java.time.OffsetDateTime
   java.util.List
   linked.map.LinkedMap
   linked.set.LinkedSet
   org.fressian.Reader
   org.fressian.StreamingWriter
   org.fressian.Writer
   org.fressian.handlers.ReadHandler
   org.fressian.handlers.WriteHandler))

(set! *warn-on-reflection* true)

(defn str->bytes
  ([^String s]
   (str->bytes s "UTF-8"))
  ([^String s, ^String encoding]
   (.getBytes s encoding)))

;; --- LOW LEVEL FRESSIAN API

(defn write-object!
  ([^Writer w ^Object o]
   (.writeObject w o))
  ([^Writer w ^Object o ^Boolean cache?]
   (.writeObject w o cache?)))

(defn read-object!
  [^Reader r]
  (.readObject r))

(defn write-tag!
  ([^Writer w ^String n]
   (.writeTag w n 1))
  ([^Writer w ^String n ^long ni]
   (.writeTag w n ni)))

(defn write-bytes!
  [^Writer w ^bytes data]
  (.writeBytes w data))

(defn write-int!
  [^Writer w ^long val]
  (.writeInt w val))

(defn write-list!
  [^Writer w ^List val]
  (.writeList w val))

;; --- READ AND WRITE HANDLERS

(defn read-symbol
  [r]
  (symbol (read-object! r)
          (read-object! r)))

(defn read-keyword
  [r]
  (keyword (read-object! r)
           (read-object! r)))

(defn write-named
  [tag ^Writer w s]
  (write-tag! w tag 2)
  (write-object! w (namespace s) true)
  (write-object! w (name s) true))

(defn write-list-like
  [tag ^Writer w o]
  (write-tag! w tag 1)
  (write-list! w o))

(defn begin-closed-list!
  [^StreamingWriter w]
  (.beginClosedList w))

(defn end-list!
  [^StreamingWriter w]
  (.endList w))

(defn write-map-like
  "Writes a map as Fressian with the tag 'map' and all keys cached."
  [tag ^Writer w m]
  (write-tag! w tag 1)
  (begin-closed-list! w)
  (loop [items (seq m)]
    (when-let [^clojure.lang.MapEntry item (first items)]
      (write-object! w (.key item) true)
      (write-object! w (.val item))
      (recur (rest items))))
  (end-list! w))

(defn read-map-like
  [^Reader rdr]
  (let [kvs ^java.util.List (read-object! rdr)]
    (if (< (.size kvs) 16)
      (clojure.lang.PersistentArrayMap. (.toArray kvs))
      (clojure.lang.PersistentHashMap/create (seq kvs)))))

(defn read-ordered-map
  [^Reader rdr]
  (let [kvs ^java.util.List (read-object! rdr)]
    (reduce #(assoc %1 (first %2) (second %2))
            (d/ordered-map)
            (partition-all 2 (seq kvs)))))

(def ^:dynamic *write-handler-lookup* nil)
(def ^:dynamic *read-handler-lookup* nil)

(def write-handlers (atom {}))
(def read-handlers (atom {}))

(defn add-handlers!
  [& handlers]
  (letfn [(adapt-write-handler [{:keys [name class wfn]}]
            [class {name (reify WriteHandler
                           (write [_ w o]
                             (wfn name w o)))}])

          (adapt-read-handler [{:keys [name rfn]}]
            [name (reify ReadHandler
                    (read [_ rdr _ _]
                      (rfn rdr)))])

          (merge-and-clean [m1 m2]
            (-> (merge m1 m2)
                (d/without-nils)))]

    (let [whs (into {}
                    (comp
                     (filter :wfn)
                     (map adapt-write-handler))
                    handlers)
          rhs (into {}
                    (comp
                     (filter :rfn)
                     (map adapt-read-handler))
                    handlers)
          cwh (swap! write-handlers merge-and-clean whs)
          crh (swap! read-handlers merge-and-clean rhs)]

      (alter-var-root #'*write-handler-lookup* (constantly (-> cwh fres/associative-lookup fres/inheritance-lookup)))
      (alter-var-root #'*read-handler-lookup* (constantly (-> crh fres/associative-lookup)))
      nil)))

(defn write-char
  [n w o]
  (write-tag! w n 1)
  (write-int! w (int o)))

(defn read-char
  [rdr]
  (char (read-object! rdr)))

(defn write-instant
  [n w o]
  (write-tag! w n 1)
  (write-int! w (.toEpochMilli ^Instant o)))

(defn write-offset-date-time
  [n w o]
  (write-tag! w n 1)
  (write-int! w (.toEpochMilli ^Instant (.toInstant ^OffsetDateTime o))))

(defn read-instant
  [rdr]
  (Instant/ofEpochMilli (.readInt ^Reader rdr)))

(defn write-ratio
  [n w o]
  (write-tag! w n 2)
  (write-object! w (.numerator ^Ratio o))
  (write-object! w (.denominator ^Ratio o)))

(defn read-ratio
  [rdr]
  (Ratio. (biginteger (read-object! rdr))
          (biginteger (read-object! rdr))))

(defn write-bigint
  [n w o]
  (let [^BigInteger bi (if (instance? clojure.lang.BigInt o)
                         (.toBigInteger ^clojure.lang.BigInt o)
                         o)]
    (write-tag! w n 1)
    (write-bytes! w (.toByteArray bi))))

(defn read-bigint
  [rdr]
  (let [^bytes bibytes (read-object! rdr)]
    (bigint (BigInteger. bibytes))))

(add-handlers!
 {:name "char"
  :class Character
  :wfn write-char
  :rfn read-char}

 {:name "java/instant"
  :class Instant
  :wfn write-instant
  :rfn read-instant}

 {:name "java/instant"
  :class OffsetDateTime
  :wfn write-offset-date-time
  :rfn read-instant}

 ;; LEGACY
 {:name "ratio"
  :rfn read-ratio}

 {:name "clj/ratio"
  :class Ratio
  :wfn write-ratio
  :rfn read-ratio}

 {:name "clj/map"
  :class clojure.lang.IPersistentMap
  :wfn write-map-like
  :rfn read-map-like}

 {:name "linked/map"
  :class LinkedMap
  :wfn write-map-like
  :rfn read-ordered-map}

 {:name "clj/keyword"
  :class clojure.lang.Keyword
  :wfn write-named
  :rfn read-keyword}

 {:name "clj/symbol"
  :class clojure.lang.Symbol
  :wfn write-named
  :rfn read-symbol}

 ;; LEGACY
 {:name "bigint"
  :rfn read-bigint}

 {:name "clj/bigint"
  :class clojure.lang.BigInt
  :wfn write-bigint
  :rfn read-bigint}

 {:name "clj/set"
  :class clojure.lang.IPersistentSet
  :wfn write-list-like
  :rfn (comp set read-object!)}

 {:name "clj/vector"
  :class clojure.lang.IPersistentVector
  :wfn write-list-like
  :rfn (comp vec read-object!)}

 {:name "clj/list"
  ;; :class clojure.lang.IPersistentList
  ;; :wfn write-list-like
  :rfn #(apply list (read-object! %))}

 {:name "clj/seq"
  :class clojure.lang.ISeq
  :wfn write-list-like
  :rfn (comp sequence read-object!)}

 {:name "linked/set"
  :class LinkedSet
  :wfn write-list-like
  :rfn (comp #(into (d/ordered-set) %) read-object!)})

;; --- PUBLIC API

(defn reader
  [istream]
  (fres/create-reader istream :handlers *read-handler-lookup*))

(defn writer
  [ostream]
  (fres/create-writer ostream :handlers *write-handler-lookup*))

(defn read!
  [reader]
  (fres/read-object reader))

(defn write!
  [writer data]
  (fres/write-object writer data))

(defn encode
  [data]
  (with-open [^ByteArrayOutputStream output (ByteArrayOutputStream.)]
    (-> (writer output)
        (write! data))
    (.toByteArray output)))

(defn decode
  [data]
  (with-open [^ByteArrayInputStream input (ByteArrayInputStream. ^bytes data)]
    (-> input reader read!)))
