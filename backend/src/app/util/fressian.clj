;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.fressian
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [clojure.data.fressian :as fres])
  (:import
   app.common.geom.matrix.Matrix
   app.common.geom.point.Point
   clojure.lang.Ratio
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.time.Instant
   java.time.OffsetDateTime
   org.fressian.Reader
   org.fressian.StreamingWriter
   org.fressian.Writer
   org.fressian.handlers.ReadHandler
   org.fressian.handlers.WriteHandler))

;; --- MISC

(set! *warn-on-reflection* true)

(defn str->bytes
  ([^String s]
   (str->bytes s "UTF-8"))
  ([^String s, ^String encoding]
   (.getBytes s encoding)))

(defn write-named
  [tag ^Writer w s]
  (.writeTag w tag 2)
  (.writeObject w (namespace s) true)
  (.writeObject w (name s) true))

(defn write-list-like
  ([^Writer w tag o]
   (.writeTag w tag 1)
   (.writeList w o)))

(defn read-list-like
  [^Reader rdr build-fn]
  (build-fn (.readObject rdr)))

(defn write-map-like
  "Writes a map as Fressian with the tag 'map' and all keys cached."
  [^Writer w tag m]
  (.writeTag w tag 1)
  (.beginClosedList ^StreamingWriter w)
  (loop [items (seq m)]
    (when-let [^clojure.lang.MapEntry item (first items)]
      (.writeObject w (.key item) true)
      (.writeObject w (.val item))
      (recur (rest items))))
  (.endList ^StreamingWriter w))

(defn read-map-like
  [^Reader rdr]
  (let [kvs ^java.util.List (.readObject rdr)]
    (if (< (.size kvs) 16)
      (clojure.lang.PersistentArrayMap. (.toArray kvs))
      (clojure.lang.PersistentHashMap/create (seq kvs)))))

(def write-handlers
  { Character
   {"char"
    (reify WriteHandler
      (write [_ w ch]
        (.writeTag w "char" 1)
        (.writeInt w (int ch))))}

   app.common.geom.point.Point
   {"penpot/point"
    (reify WriteHandler
      (write [_ w o]
        (.writeTag ^Writer w "penpot/point" 1)
        (.writeList ^Writer w (java.util.List/of (.-x ^Point o) (.-y ^Point o)))))}

   app.common.geom.matrix.Matrix
   {"penpot/matrix"
    (reify WriteHandler
      (write [_ w o]
        (.writeTag ^Writer w "penpot/matrix" 1)
        (.writeList ^Writer w (java.util.List/of (.-a ^Matrix o)
                                                 (.-b ^Matrix o)
                                                 (.-c ^Matrix o)
                                                 (.-d ^Matrix o)
                                                 (.-e ^Matrix o)
                                                 (.-f ^Matrix o)))))}

   Instant
   {"java/instant"
    (reify WriteHandler
      (write [_ w ch]
        (.writeTag w "java/instant" 1)
        (.writeInt w (.toEpochMilli ^Instant ch))))}

   OffsetDateTime
   {"java/instant"
    (reify WriteHandler
      (write [_ w ch]
        (.writeTag w "java/instant" 1)
        (.writeInt w (.toEpochMilli ^Instant (.toInstant ^OffsetDateTime ch)))))}

   Ratio
   {"ratio"
    (reify WriteHandler
      (write [_ w n]
        (.writeTag w "ratio" 2)
        (.writeObject w (.numerator ^Ratio n))
        (.writeObject w (.denominator ^Ratio n))))}

   clojure.lang.IPersistentMap
   {"clj/map"
    (reify WriteHandler
      (write [_ w d]
        (write-map-like w "clj/map" d)))}

   clojure.lang.Keyword
   {"clj/keyword"
    (reify WriteHandler
      (write [_ w s]
        (write-named "clj/keyword" w s)))}

   clojure.lang.BigInt
   {"bigint"
    (reify WriteHandler
      (write [_ w d]
        (let [^BigInteger bi (if (instance? clojure.lang.BigInt d)
                               (.toBigInteger ^clojure.lang.BigInt d)
                               d)]
          (.writeTag w "bigint" 1)
          (.writeBytes w (.toByteArray bi)))))}

   ;; Persistent set
   clojure.lang.IPersistentSet
   {"clj/set"
    (reify WriteHandler
      (write [_ w o]
        (write-list-like w "clj/set" o)))}

   ;; Persistent vector
   clojure.lang.IPersistentVector
   {"clj/vector"
    (reify WriteHandler
      (write [_ w o]
        (write-list-like w "clj/vector" o)))}

   ;; Persistent list
   clojure.lang.IPersistentList
   {"clj/list"
    (reify WriteHandler
      (write [_ w o]
        (write-list-like w "clj/list" o)))}

   ;; Persistent seq & lazy seqs
   clojure.lang.ISeq
   {"clj/seq"
    (reify WriteHandler
      (write [_ w o]
        (write-list-like w "clj/seq" o)))}
   })


(def read-handlers
  {"bigint"
   (reify ReadHandler
     (read [_ rdr _ _]
       (let [^bytes bibytes (.readObject rdr)]
         (bigint (BigInteger. bibytes)))))

   "byte"
   (reify ReadHandler
     (read [_ rdr _ _]
       (byte (.readObject rdr))))

   "penpot/matrix"
   (reify ReadHandler
     (read [_ rdr _ _]
       (let [^java.util.List x (.readObject rdr)]
         (Matrix. (.get x 0) (.get x 1) (.get x 2) (.get x 3) (.get x 4) (.get x 5)))))

   "penpot/point"
   (reify ReadHandler
     (read [_ rdr _ _]
       (let [^java.util.List x (.readObject rdr)]
         (Point. (.get x 0) (.get x 1)))))

   "char"
   (reify ReadHandler
     (read [_ rdr _ _]
       (char (.readObject rdr))))

   "java/instant"
   (reify ReadHandler
     (read [_ rdr _ _]
       (Instant/ofEpochMilli (.readInt rdr))))


   "ratio"
   (reify ReadHandler
     (read [_ rdr _ _]
       (Ratio. (biginteger (.readObject rdr))
               (biginteger (.readObject rdr)))))

   "clj/keyword"
   (reify ReadHandler
     (read [_ rdr _ _]
       (keyword (.readObject rdr) (.readObject rdr))))

   "clj/map"
   (reify ReadHandler
     (read [_ rdr _ _]
       (read-map-like rdr)))

   "clj/set"
   (reify ReadHandler
     (read [_ rdr _ _]
       (read-list-like rdr set)))

   "clj/vector"
   (reify ReadHandler
     (read [_ rdr _ _]
       (read-list-like rdr vec)))

   "clj/list"
   (reify ReadHandler
     (read [_ rdr _ _]
       (read-list-like rdr #(apply list %))))

   "clj/seq"
   (reify ReadHandler
     (read [_ rdr _ _]
       (read-list-like rdr sequence)))
   })

(def write-handler-lookup
  (-> write-handlers
      fres/associative-lookup
      fres/inheritance-lookup))

(def read-handler-lookup
  (-> read-handlers
      (fres/associative-lookup)))

;; --- Low-Level Api

(defn reader
  [istream]
  (fres/create-reader istream :handlers read-handler-lookup))

(defn writer
  [ostream]
  (fres/create-writer ostream :handlers write-handler-lookup))

(defn read!
  [reader]
  (fres/read-object reader))

(defn write!
  [writer data]
  (fres/write-object writer data))

;; --- High-Level Api

(defn encode
  [data]
  (with-open [out (ByteArrayOutputStream.)]
    (write! (writer out) data)
    (.toByteArray out)))

(defn decode
  [data]
  (with-open [input (ByteArrayInputStream. ^bytes data)]
    (read! (reader input))))
