;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path.impl
  "Contains schemas and data type implementation for PathData binary
  and plain formats"
  (:refer-clojure :exclude [-lookup -reduce])
  #?(:cljs (:require-macros [app.common.types.path.impl]))
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as json])
   #?(:cljs [app.common.weak :as weak])
   [app.common.buffer :as buf]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as oapi]
   [app.common.svg.path :as svg.path]
   [app.common.transit :as t]
   [app.common.types.path :as-alias path]
   [cuerdas.core :as str])
  (:import
   #?(:cljs [goog.string StringBuffer]
      :clj  [java.nio ByteBuffer ByteOrder])))

#?(:clj (set! *warn-on-reflection* true))

(def ^:const SEGMENT-U8-SIZE 28)
(def ^:const SEGMENT-U32-SIZE (/ SEGMENT-U8-SIZE 4))

(defprotocol IPathData
  (-write-to [_ buffer offset] "write the content to the specified buffer")
  (-get-byte-size [_] "get byte size"))

(defprotocol ITransformable
  (-transform [_ m] "apply a transform")
  (-lookup [_ index f])
  (-walk [_ f initial])
  (-reduce [_ f initial]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-cache
  "A helper macro that facilitates cache handling for content
  instance, only relevant on CLJS"
  [target key & expr]
  (if (:ns &env)
    (let [target (with-meta target {:tag 'js})]
      `(let [~'cache  (.-cache ~target)
             ~'result (.get ~'cache ~key)]
         (if ~'result
           (do
             ~'result)
           (let [~'result (do ~@expr)]
             (.set ~'cache ~key ~'result)
             ~'result))))
    `(do ~@expr)))

(defn- impl-transform-segment
  "Apply a transformation to a segment located under specified offset"
  [buffer offset a b c d e f]
  (let [t (buf/read-short buffer offset)]
    (case t
      (1 2)
      (let [x  (buf/read-float buffer (+ offset 20))
            y  (buf/read-float buffer (+ offset 24))
            x' (+ (* x a) (* y c) e)
            y' (+ (* x b) (* y d) f)]
        (buf/write-float buffer (+ offset 20) x')
        (buf/write-float buffer (+ offset 24) y'))

      3
      (let [c1x  (buf/read-float buffer (+ offset 4))
            c1y  (buf/read-float buffer (+ offset 8))
            c2x  (buf/read-float buffer (+ offset 12))
            c2y  (buf/read-float buffer (+ offset 16))
            x    (buf/read-float buffer (+ offset 20))
            y    (buf/read-float buffer (+ offset 24))

            c1x' (+ (* c1x a) (* c1y c) e)
            c1y' (+ (* c1x b) (* c1y d) f)
            c2x' (+ (* c2x a) (* c2y c) e)
            c2y' (+ (* c2x b) (* c2y d) f)
            x'   (+ (* x a) (* y c) e)
            y'   (+ (* x b) (* y d) f)]

        (buf/write-float buffer (+ offset 4) c1x')
        (buf/write-float buffer (+ offset 8) c1y')
        (buf/write-float buffer (+ offset 12) c2x')
        (buf/write-float buffer (+ offset 16) c2y')
        (buf/write-float buffer (+ offset 20) x')
        (buf/write-float buffer (+ offset 24) y'))

      nil)))

(defn- impl-transform
  [buffer m size]
  (let [a (dm/get-prop m :a)
        b (dm/get-prop m :b)
        c (dm/get-prop m :c)
        d (dm/get-prop m :d)
        e (dm/get-prop m :e)
        f (dm/get-prop m :f)]
    (loop [index 0]
      (when (< index size)
        (let [offset (* index SEGMENT-U8-SIZE)]
          (impl-transform-segment buffer offset a b c d e f)
          (recur (inc index)))))))

(defn- impl-walk
  [buffer f initial size]
  (loop [index 0
         result (transient initial)]
    (if (< index size)
      (let [offset (* index SEGMENT-U8-SIZE)
            type   (buf/read-short buffer offset)
            c1x    (buf/read-float buffer (+ offset 4))
            c1y    (buf/read-float buffer (+ offset 8))
            c2x    (buf/read-float buffer (+ offset 12))
            c2y    (buf/read-float buffer (+ offset 16))
            x      (buf/read-float buffer (+ offset 20))
            y      (buf/read-float buffer (+ offset 24))
            type   (case type
                     1 :line-to
                     2 :move-to
                     3 :curve-to
                     4 :close-path)
            res    (f type c1x c1y c2x c2y x y)]
        (recur (inc index)
               (if (some? res)
                 (conj! result res)
                 result)))
      (persistent! result))))

(defn impl-reduce
  [buffer f initial size]
  (loop [index 0
         result initial]
    (if (< index size)
      (let [offset (* index SEGMENT-U8-SIZE)
            type   (buf/read-short buffer offset)
            c1x    (buf/read-float buffer (+ offset 4))
            c1y    (buf/read-float buffer (+ offset 8))
            c2x    (buf/read-float buffer (+ offset 12))
            c2y    (buf/read-float buffer (+ offset 16))
            x      (buf/read-float buffer (+ offset 20))
            y      (buf/read-float buffer (+ offset 24))
            type   (case type
                     1 :line-to
                     2 :move-to
                     3 :curve-to
                     4 :close-path)
            result (f result index type c1x c1y c2x c2y x y)]
        (if (reduced? result)
          result
          (recur (inc index) result)))
      result)))

(defn impl-lookup
  [buffer index f]
  (let [offset (* index SEGMENT-U8-SIZE)
        type   (buf/read-short buffer offset)
        c1x    (buf/read-float buffer (+ offset 4))
        c1y    (buf/read-float buffer (+ offset 8))
        c2x    (buf/read-float buffer (+ offset 12))
        c2y    (buf/read-float buffer (+ offset 16))
        x      (buf/read-float buffer (+ offset 20))
        y      (buf/read-float buffer (+ offset 24))
        type   (case type
                 1 :line-to
                 2 :move-to
                 3 :curve-to
                 4 :close-path)]
    #?(:clj (f type c1x c1y c2x c2y x y)
       :cljs (^function f type c1x c1y c2x c2y x y))))

(defn- to-string-segment*
  [buffer offset type ^StringBuilder builder]
  (case (long type)
    1 (let [x (buf/read-float buffer (+ offset 20))
            y (buf/read-float buffer (+ offset 24))]
        (doto builder
          (.append "M")
          (.append x)
          (.append ",")
          (.append y)))
    2 (let [x (buf/read-float buffer (+ offset 20))
            y (buf/read-float buffer (+ offset 24))]
        (doto builder
          (.append "L")
          (.append x)
          (.append ",")
          (.append y)))

    3 (let [c1x (buf/read-float buffer (+ offset 4))
            c1y (buf/read-float buffer (+ offset 8))
            c2x (buf/read-float buffer (+ offset 12))
            c2y (buf/read-float buffer (+ offset 16))
            x   (buf/read-float buffer (+ offset 20))
            y   (buf/read-float buffer (+ offset 24))]
        (doto builder
          (.append "C")
          (.append c1x)
          (.append ",")
          (.append c1y)
          (.append ",")
          (.append c2x)
          (.append ",")
          (.append c2y)
          (.append ",")
          (.append x)
          (.append ",")
          (.append y)))
    4 (doto builder
        (.append "Z"))))

(defn- to-string
  "Format the path data structure to string"
  [buffer size]
  (let [builder #?(:clj (java.lang.StringBuilder. (int (* size 4)))
                   :cljs (StringBuffer.))]
    (loop [index 0]
      (when (< index size)
        (let [offset (* index SEGMENT-U8-SIZE)
              type   (buf/read-short buffer offset)]
          (to-string-segment* buffer offset type builder)
          (recur (inc index)))))

    (.toString builder)))

(defn- read-segment
  "Read segment from binary buffer at specified index"
  [buffer index]
  (let [offset (* index SEGMENT-U8-SIZE)
        type   (buf/read-short buffer offset)]
    (case (long type)
      1 (let [x (buf/read-float buffer (+ offset 20))
              y (buf/read-float buffer (+ offset 24))]
          {:command :move-to
           :params {:x (double x)
                    :y (double y)}})

      2 (let [x (buf/read-float buffer (+ offset 20))
              y (buf/read-float buffer (+ offset 24))]
          {:command :line-to
           :params {:x (double x)
                    :y (double y)}})

      3 (let [c1x (buf/read-float buffer (+ offset 4))
              c1y (buf/read-float buffer (+ offset 8))
              c2x (buf/read-float buffer (+ offset 12))
              c2y (buf/read-float buffer (+ offset 16))
              x   (buf/read-float buffer (+ offset 20))
              y   (buf/read-float buffer (+ offset 24))]
          {:command :curve-to
           :params {:x (double x)
                    :y (double y)
                    :c1x (double c1x)
                    :c1y (double c1y)
                    :c2x (double c2x)
                    :c2y (double c2y)}})

      4 {:command :close-path
         :params {}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TYPE: PATH-DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (deftype PathData [size
                      ^ByteBuffer buffer
                      ^:unsynchronized-mutable hash]
     Object
     (toString [_]
       (to-string buffer size))

     (equals [_ other]
       (if (instance? PathData other)
         (buf/equals? buffer (.-buffer ^PathData other))
         false))

     ITransformable
     (-transform [_ m]
       (let [buffer (buf/clone buffer)]
         (impl-transform buffer m size)
         (PathData. size buffer nil)))

     (-walk [_ f initial]
       (impl-walk buffer f initial size))

     (-reduce [_ f initial]
       (impl-reduce buffer f initial size))

     (-lookup [_ index f]
       (when (and (<= 0 index)
                  (< index size))
         (impl-lookup buffer index f)))

     json/JSONWriter
     (-write [this writter options]
       (json/-write (.toString this) writter options))

     clojure.lang.IHashEq
     (hasheq [this]
       (when-not hash
         (set! hash (clojure.lang.Murmur3/hashOrdered (vec this))))
       hash)

     clojure.lang.Sequential
     clojure.lang.Seqable
     (seq [_]
       (when (pos? size)
         ((fn next-seq [i]
            (when (< i size)
              (cons (read-segment buffer i)
                    (lazy-seq (next-seq (inc i))))))
          0)))

     clojure.lang.IReduceInit
     (reduce [_ f start]
       (loop [index  0
              result start]
         (if (< index size)
           (let [result (f result (read-segment buffer index))]
             (if (reduced? result)
               @result
               (recur (inc index) result)))
           result)))

     clojure.lang.Indexed
     (nth [_ i]
       (if (d/in-range? size i)
         (read-segment buffer i)
         nil))

     (nth [_ i default]
       (if (d/in-range? size i)
         (read-segment buffer i)
         default))

     clojure.lang.Counted
     (count [_] size)

     IPathData
     (-get-byte-size [_]
       (* size SEGMENT-U8-SIZE))

     (-write-to [_ _ _]
       (throw (RuntimeException. "not implemented"))))

   :cljs
   #_:clj-kondo/ignore
   (deftype PathData [size buffer cache ^:mutable __hash]
     Object
     (toString [_]
       (to-string buffer size))

     IPathData
     (-get-byte-size [_]
       (.-byteLength buffer))

     (-write-to [_ into-buffer offset]
       ;; NOTE: we still use u8 because until the heap refactor merge
       ;; we can't guarrantee the alignment of offset on 4 bytes
       (assert (instance? js/ArrayBuffer into-buffer))
       (let [buffer' (.-buffer ^js/DataView buffer)
             size    (.-byteLength buffer')
             mem     (js/Uint8Array. into-buffer offset size)]
         (.set mem (js/Uint8Array. buffer'))))

     ITransformable
     (-transform [this m]
       (let [buffer (buf/clone buffer)]
         (impl-transform buffer m size)
         (PathData. size buffer (weak/weak-value-map) nil)))

     (-walk [_ f initial]
       (impl-walk buffer f initial size))

     (-reduce [_ f initial]
       (impl-reduce buffer f initial size))

     (-lookup [_ index f]
       (when (and (<= 0 index)
                  (< index size))
         (impl-lookup buffer index f)))

     cljs.core/ISequential
     cljs.core/IEquiv
     (-equiv [this other]
       (if (instance? PathData other)
         (buf/equals? buffer (.-buffer other))
         false))

     cljs.core/IReduce
     (-reduce [_ f]
       (loop [index  1
              result (if (pos? size)
                       (read-segment buffer 0)
                       nil)]
         (if (< index size)
           (let [result (f result (read-segment buffer index))]
             (if (reduced? result)
               @result
               (recur (inc index) result)))
           result)))

     (-reduce [_ f start]
       (loop [index  0
              result start]
         (if (< index size)
           (let [result (f result (read-segment buffer index))]
             (if (reduced? result)
               @result
               (recur (inc index) result)))
           result)))

     cljs.core/IHash
     (-hash [coll]
       (caching-hash coll hash-ordered-coll __hash))

     cljs.core/ICounted
     (-count [_] size)

     cljs.core/IIndexed
     (-nth [_ i]
       (if (d/in-range? size i)
         (read-segment buffer i)
         nil))

     (-nth [_ i default]
       (if (d/in-range? i size)
         (read-segment buffer i)
         default))

     cljs.core/ISeqable
     (-seq [this]
       (when (pos? size)
         ((fn next-seq [i]
            (when (< i size)
              (cons (read-segment buffer i)
                    (lazy-seq (next-seq (inc i))))))
          0)))

     cljs.core/IPrintWithWriter
     (-pr-writer [this writer _]
       (cljs.core/-write writer (str "#penpot/path-data \"" (.toString this) "\"")))))

#?(:clj
   (defmethod print-method PathData
     [o w]
     (print-dup o w)))

#?(:clj
   (defmethod print-dup PathData
     [^PathData o ^java.io.Writer writer]
     (.write writer (str "#penpot/path-data \"" (.toString o) "\""))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:safe-number
  [:schema {:gen/gen (sg/small-int :max 100 :min -100)}
   ::sm/safe-number])

(def ^:private schema:line-to-segment
  [:map
   [:command [:= :line-to]]
   [:params
    [:map
     [:x schema:safe-number]
     [:y schema:safe-number]]]])

(def ^:private schema:close-path-segment
  [:map
   [:command [:= :close-path]]])

(def ^:private schema:move-to-segment
  [:map
   [:command [:= :move-to]]
   [:params
    [:map
     [:x schema:safe-number]
     [:y schema:safe-number]]]])

(def ^:private schema:curve-to-segment
  [:map
   [:command [:= :curve-to]]
   [:params
    [:map
     [:x schema:safe-number]
     [:y schema:safe-number]
     [:c1x {:optional true} schema:safe-number]
     [:c1y {:optional true} schema:safe-number]
     [:c2x {:optional true} schema:safe-number]
     [:c2y {:optional true} schema:safe-number]]]])

(def ^:private schema:segment
  [:multi {:title "PathSegment"
           :dispatch :command
           :decode/json #(update % :command keyword)}
   [:line-to schema:line-to-segment]
   [:close-path schema:close-path-segment]
   [:move-to schema:move-to-segment]
   [:curve-to schema:curve-to-segment]])

(def schema:segments
  [:vector {:gen/gen (->> (sg/generator schema:segment)
                          (sg/vector)
                          (sg/filter not-empty)
                          (sg/filter (fn [[e1]]
                                       (= (:command e1) :move-to))))}
   schema:segment])

(defn path-data?
  [o]
  (instance? PathData o))

(declare from-string)
(declare from-plain)

(def schema:content
  (sm/type-schema
   {:type ::path/content
    :compile
    (fn [_ _ _]
      (let [decoder   (delay (sm/decoder schema:segments sm/json-transformer))
            generator (->> (sg/generator schema:segments)
                           (sg/filter not-empty)
                           (sg/fmap from-plain))]
        {:pred path-data?
         :type-properties
         {::oapi/type "string"
          :gen/gen generator
          :encode/json identity
          :decode/json (fn [s]
                         (cond
                           (string? s)
                           (if (str/empty? s)
                             (from-plain [])
                             (from-string s))

                           (vector? s)
                           (let [decode-fn (deref decoder)]
                             (-> (decode-fn s)
                                 (from-plain)))

                           :else
                           s))}}))}))

(def check-plain-content
  (sm/check-fn schema:segments))

(def check-segment
  (sm/check-fn schema:segment))

(def check-content
  (sm/check-fn schema:content))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONSTRUCTORS & PREDICATES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn from-string
  [s]
  (from-plain (svg.path/parse s)))

(defn from-bytes
  [buffer]
  #?(:clj
     (cond
       (instance? ByteBuffer buffer)
       (let [size   (.capacity ^ByteBuffer buffer)
             count  (long (/ size SEGMENT-U8-SIZE))
             buffer (.order ^ByteBuffer buffer ByteOrder/LITTLE_ENDIAN)]
         (PathData. count buffer nil))

       (bytes? buffer)
       (let [size   (alength ^bytes buffer)
             count  (long (/ size SEGMENT-U8-SIZE))
             buffer (ByteBuffer/wrap buffer)]
         (PathData. count
                    (.order buffer ByteOrder/LITTLE_ENDIAN)
                    nil))
       :else
       (throw (java.lang.IllegalArgumentException. "invalid data provided")))

     :cljs
     (cond
       (instance? js/ArrayBuffer buffer)
       (let [size  (.-byteLength buffer)
             count (long (/ size SEGMENT-U8-SIZE))]
         (PathData. count
                    (js/DataView. buffer)
                    (weak/weak-value-map)
                    nil))

       (instance? js/DataView buffer)
       (let [buffer' (.-buffer ^js/DataView buffer)
             size    (.-byteLength ^js/ArrayBuffer buffer')
             count   (long (/ size SEGMENT-U8-SIZE))]
         (PathData. count buffer (weak/weak-value-map) nil))

       (instance? js/Uint8Array buffer)
       (from-bytes (.-buffer buffer))

       (instance? js/Uint32Array buffer)
       (from-bytes (.-buffer buffer))

       (instance? js/Int8Array buffer)
       (from-bytes (.-buffer buffer))

       :else
       (throw (js/Error. "invalid data provided")))))

;; FIXME: consider implementing with reduce
;; FIXME: consider ensure fixed precision for avoid doing it on formatting

(defn from-plain
  "Create a PathData instance from plain data structures"
  [segments]
  (assert (check-plain-content segments))

  (let [total  (count segments)
        buffer (buf/allocate (* total SEGMENT-U8-SIZE))]
    (loop [index 0]
      (when (< index total)
        (let [segment (nth segments index)
              offset  (* index SEGMENT-U8-SIZE)]
          (case (get segment :command)
            :move-to
            (let [params (get segment :params)
                  x      (float (get params :x))
                  y      (float (get params :y))]
              (buf/write-short buffer offset 1)
              (buf/write-float buffer (+ offset 20) x)
              (buf/write-float buffer (+ offset 24) y))

            :line-to
            (let [params (get segment :params)
                  x      (float (get params :x))
                  y      (float (get params :y))]

              (buf/write-short buffer offset 2)
              (buf/write-float buffer (+ offset 20) x)
              (buf/write-float buffer (+ offset 24) y))

            :curve-to
            (let [params (get segment :params)
                  x      (float (get params :x))
                  y      (float (get params :y))
                  c1x    (float (get params :c1x x))
                  c1y    (float (get params :c1y y))
                  c2x    (float (get params :c2x x))
                  c2y    (float (get params :c2y y))]

              (buf/write-short buffer offset 3)
              (buf/write-float buffer (+ offset 4)  c1x)
              (buf/write-float buffer (+ offset 8)  c1y)
              (buf/write-float buffer (+ offset 12) c2x)
              (buf/write-float buffer (+ offset 16) c2y)
              (buf/write-float buffer (+ offset 20) x)
              (buf/write-float buffer (+ offset 24) y))

            :close-path
            (buf/write-short buffer offset 4))
          (recur (inc index)))))

    (from-bytes buffer)))

(defn path-data
  "Create an instance of PathData, returns itself if it is already
  PathData instance"
  [data]
  (cond
    (path-data? data)
    data

    (nil? data)
    (from-plain [])

    (sequential? data)
    (from-plain data)

    :else
    (throw (ex-info "unexpected data" {:data data}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/add-handlers!
 {:id "penpot/path-data"
  :class PathData
  :wfn (fn [^PathData pdata]
         (let [buffer (.-buffer pdata)]
           #?(:cljs (js/Uint8Array. (.-buffer ^js/DataView buffer))
              :clj  (.array ^ByteBuffer buffer))))
  :rfn from-bytes})

#?(:clj
   (fres/add-handlers!
    {:name "penpot/path-data"
     :class PathData
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (let [buffer (.-buffer ^PathData o)
                  bytes  (.array ^ByteBuffer buffer)]
              (fres/write-bytes! w bytes)))
     :rfn (fn [r]
            (let [^bytes bytes (fres/read-object! r)]
              (from-bytes bytes)))}))

