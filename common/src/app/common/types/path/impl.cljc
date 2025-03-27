;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path.impl
  "Contains schemas and data type implementation for PathData binary
  and plain formats"
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as json])
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.svg.path :as svg.path]
   [app.common.transit :as t]
   [app.common.types.path :as-alias path])
  (:import
   #?(:cljs [goog.string StringBuffer]
      :clj  [java.nio ByteBuffer])))

#?(:clj (set! *warn-on-reflection* true))

(def ^:const SEGMENT-BYTE-SIZE 28)

(defprotocol IPathData
  (-write-to [_ buffer offset] "write the content to the specified buffer")
  (-get-byte-size [_] "get byte size"))

(defprotocol ITransformable
  (-transform [_ m] "apply a transform"))

(defn- transform!
  "Apply a transformation to a segment located under specified offset"
  [buffer offset m]
  (let [a (dm/get-prop m :a)
        b (dm/get-prop m :b)
        c (dm/get-prop m :c)
        d (dm/get-prop m :d)
        e (dm/get-prop m :e)
        f (dm/get-prop m :f)
        t #?(:clj  (.getShort ^ByteBuffer buffer offset)
             :cljs (.getInt16 buffer offset))]

    (case t
      (1 2)
      (let [x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                 :cljs (.getFloat32 buffer (+ offset 20)))
            y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                 :cljs (.getFloat32 buffer (+ offset 24)))
            x (+ (* x a) (* y c) e)
            y (+ (* x b) (* y d) f)]
        #?(:clj  (.putFloat ^ByteBuffer buffer (+ offset 20) x)
           :cljs (.setFloat32 buffer (+ offset 20) x))
        #?(:clj  (.putFloat ^ByteBuffer buffer (+ offset 24) y)
           :cljs (.setFloat32 buffer (+ offset 24) y)))

      3
      (let [c1x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 4))
                   :cljs (.getFloat32 buffer (+ offset 4)))
            c1y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 8))
                   :cljs (.getFloat32 buffer (+ offset 8)))
            c2x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 12))
                   :cljs (.getFloat32 buffer (+ offset 12)))
            c2y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 16))
                   :cljs (.getFloat32 buffer (+ offset 16)))
            x   #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                   :cljs (.getFloat32 buffer (+ offset 20)))
            y   #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                   :cljs (.getFloat32 buffer (+ offset 24)))

            c1x (+ (* c1x a) (* c1y c) e)
            c1y (+ (* c1x b) (* c1y d) f)
            c2x (+ (* c2x a) (* c2y c) e)
            c2y (+ (* c2x b) (* c2y d) f)
            x   (+ (* x a) (* y c) e)
            y   (+ (* x b) (* y d) f)]

        #?(:clj  (.putFloat ^ByteBuffer buffer (+ offset 4) c1x)
           :cljs (.setFloat32 buffer (+ offset 4) c1x))
        #?(:clj  (.putFloat ^ByteBuffer buffer (+ offset 8) c1y)
           :cljs (.setFloat32 buffer (+ offset 8) c1y))
        #?(:clj  (.putFloat ^ByteBuffer buffer (+ offset 12) c2x)
           :cljs (.setFloat32 buffer (+ offset 12) c2x))
        #?(:clj  (.putFloat ^ByteBuffer buffer (+ offset 16) c2y)
           :cljs (.setFloat32 buffer (+ offset 16) c2y))
        #?(:clj  (.putFloat ^ByteBuffer buffer (+ offset 20) x)
           :cljs (.setFloat32 buffer (+ offset 20) x))
        #?(:clj  (.putFloat ^ByteBuffer buffer (+ offset 24) y)
           :cljs (.setFloat32 buffer (+ offset 24) y)))

      nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TYPE: PATH-DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- to-string
  "Format the path data structure to string"
  [buffer size]
  (let [builder #?(:clj (java.lang.StringBuilder. (int (* size 4)))
                   :cljs (StringBuffer.))]
    (loop [index 0]
      (when (< index size)
        (let [offset (* index SEGMENT-BYTE-SIZE)
              type   #?(:clj  (.getShort ^ByteBuffer buffer offset)
                        :cljs (.getInt16 buffer offset))]
          (case (long type)
            1 (let [x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                         :cljs (.getFloat32 buffer (+ offset 20)))
                    y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                         :cljs (.getFloat32 buffer (+ offset 24)))]
                (doto builder
                  (.append "M")
                  (.append x)
                  (.append ",")
                  (.append y)))
            2 (let [x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                         :cljs (.getFloat32 buffer (+ offset 20)))
                    y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                         :cljs (.getFloat32 buffer (+ offset 24)))]
                (doto builder
                  (.append "L")
                  (.append x)
                  (.append ",")
                  (.append y)))

            3 (let [c1x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 4))
                           :cljs (.getFloat32 buffer (+ offset 4)))
                    c1y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 8))
                           :cljs (.getFloat32 buffer (+ offset 8)))
                    c2x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 12))
                           :cljs (.getFloat32 buffer (+ offset 12)))
                    c2y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 16))
                           :cljs (.getFloat32 buffer (+ offset 16)))
                    x   #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                           :cljs (.getFloat32 buffer (+ offset 20)))
                    y   #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                           :cljs (.getFloat32 buffer (+ offset 24)))]
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
                (.append "Z")))
          (recur (inc index)))))

    (.toString builder)))

(defn- read-segment
  "Read segment from binary buffer at specified index"
  [buffer index]
  (let [offset (* index SEGMENT-BYTE-SIZE)
        type   #?(:clj  (.getShort ^ByteBuffer buffer offset)
                  :cljs (.getInt16 buffer offset))]
    (case (long type)
      1 (let [x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                   :cljs (.getFloat32 buffer (+ offset 20)))
              y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                   :cljs (.getFloat32 buffer (+ offset 24)))]
          {:command :move-to
           :params {:x x :y y}})

      2 (let [x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                   :cljs (.getFloat32 buffer (+ offset 20)))
              y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                   :cljs (.getFloat32 buffer (+ offset 24)))]
          {:command :line-to
           :params {:x x :y y}})

      3 (let [c1x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 4))
                     :cljs (.getFloat32 buffer (+ offset 4)))
              c1y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 8))
                     :cljs (.getFloat32 buffer (+ offset 8)))
              c2x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 12))
                     :cljs (.getFloat32 buffer (+ offset 12)))
              c2y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 16))
                     :cljs (.getFloat32 buffer (+ offset 16)))
              x   #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                     :cljs (.getFloat32 buffer (+ offset 20)))
              y   #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                     :cljs (.getFloat32 buffer (+ offset 24)))]
          {:command :curve-to
           :params {:x x :y y :c1x c1x :c1y c1y :c2x c2x :c2y c2y}})

      4 {:command :close-path
         :params {}})))

(defn- in-range?
  [size i]
  (and (< i size) (>= i 0)))

(defn- clone-buffer
  [buffer]
  #?(:clj
     (let [src (.array ^ByteBuffer buffer)
           len (alength ^bytes src)
           dst (byte-array len)]
       (System/arraycopy src 0 dst 0 len)
       (ByteBuffer/wrap dst))
     :cljs
     (let [src-view (js/Uint32Array. buffer)
           dst-buff (js/ArrayBuffer. (.-byteLength buffer))
           dst-view (js/Uint32Array. dst-buff)]
       (.set dst-view src-view)
       dst-buff)))

#?(:clj
   (deftype PathData [size buffer ^:unsynchronized-mutable hash]
     Object
     (toString [_]
       (to-string buffer size))

     (equals [_ other]
       (if (instance? PathData other)
         (.equals ^ByteBuffer buffer (.-buffer ^PathData other))
         false))

     ITransformable
     (-transform [_ m]
       (let [buffer (clone-buffer buffer)]
         (loop [index 0]
           (when (< index size)
             (let [offset (* index SEGMENT-BYTE-SIZE)]
               (transform! buffer offset m)
               (recur (inc index)))))
         (PathData. size buffer nil)))

     json/JSONWriter
     (-write [this writter options]
       (json/-write (.toString this) writter options))

     clojure.lang.IHashEq
     (hasheq [this]
       (when-not hash
         (set! hash (clojure.lang.Murmur3/hashOrdered (seq this))))
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
       (if (in-range? size i)
         (read-segment buffer i)
         nil))

     (nth [_ i default]
       (if (in-range? size i)
         (read-segment buffer i)
         default))

     clojure.lang.Counted
     (count [_] size)

     IPathData
     (-get-byte-size [_]
       (* size SEGMENT-BYTE-SIZE))

     (-write-to [_ _ _]
       (throw (RuntimeException. "not implemented"))))

   :cljs
   #_:clj-kondo/ignore
   (deftype PathData [size buffer dview ^:mutable __hash]
     Object
     (toString [_]
       (to-string dview size))

     IPathData
     (-get-byte-size [_]
       (.-byteLength buffer))

     (-write-to [_ into-buffer offset]
       ;; NOTE: we still use u8 because until the heap refactor merge
       ;; we can't guarrantee the alignment of offset on 4 bytes
       (assert (instance? js/ArrayBuffer into-buffer))
       (let [size (.-byteLength buffer)
             mem  (js/Uint8Array. into-buffer offset size)]
         (.set mem (js/Uint8Array. buffer))))

     ITransformable
     (-transform [this m]
       (let [buffer (clone-buffer buffer)
             dview  (js/DataView. buffer)]
         (loop [index 0]

           (when (< index size)
             (let [offset (* index SEGMENT-BYTE-SIZE)]
               (transform! dview offset m)
               (recur (inc index)))))
         (PathData. size buffer dview nil)))

     cljs.core/ISequential
     cljs.core/IEquiv
     (-equiv [this other]
       (if (instance? PathData other)
         (let [obuffer (.-buffer other)]
           (if (= (.-byteLength obuffer)
                  (.-byteLength buffer))
             (let [cb (js/Uint32Array. buffer)
                   ob (js/Uint32Array. obuffer)
                   sz (alength cb)]
               (loop [i 0]
                 (if (< i sz)
                   (if (= (aget ob i)
                          (aget cb i))
                     (recur (inc i))
                     false)
                   true)))
             false))
         false))

     cljs.core/IReduce
     (-reduce [_ f]
       (loop [index  1
              result (if (pos? size)
                       (read-segment dview 0)
                       nil)]
         (if (< index size)
           (let [result (f result (read-segment dview index))]
             (if (reduced? result)
               @result
               (recur (inc index) result)))
           result)))

     (-reduce [_ f start]
       (loop [index  0
              result start]
         (if (< index size)
           (let [result (f result (read-segment dview index))]
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
       (if (in-range? size i)
         (read-segment dview i)
         nil))

     (-nth [_ i default]
       (if (in-range? i size)
         (read-segment dview i)
         default))

     cljs.core/ISeqable
     (-seq [this]
       (when (pos? size)
         (->> (range size)
              (map (fn [i] (cljs.core/-nth this i))))))

     cljs.core/IPrintWithWriter
     (-pr-writer [this writer _]
       (cljs.core/-write writer (str "#penpot/path-data \"" (.toString this) "\"")))))

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
     [:c1x schema:safe-number]
     [:c1y schema:safe-number]
     [:c2x schema:safe-number]
     [:c2y schema:safe-number]]]])

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

(def schema:content-like
  [:sequential schema:segment])

(def check-content-like
  (sm/check-fn schema:content-like))

(def check-segment
  (sm/check-fn schema:segment))

(def ^:private check-segments
  (sm/check-fn schema:segments))

(sm/register! ::path/segment schema:segment)
(sm/register! ::path/segments schema:segments)

(defn path-data?
  [o]
  (instance? PathData o))

(declare from-string)
(declare from-plain)

(sm/register!
 {:type ::path/content
  :pred path-data?
  :type-properties
  {:gen/gen (->> (sg/generator schema:segments)
                 (sg/filter not-empty)
                 (sg/fmap #(from-plain %)))
   :encode/json identity
   :decode/json (fn [s]
                  (cond
                    (string? s)
                    (from-string s)

                    (vector? s)
                    (from-plain s)

                    :else
                    s))}})

(def check-path-content
  (sm/check-fn ::path/content))

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
       (let [size  (.capacity ^ByteBuffer buffer)
             count (long (/ size SEGMENT-BYTE-SIZE))]
         (PathData. count buffer nil))

       (bytes? buffer)
       (let [size  (alength ^bytes buffer)
             count (long (/ size SEGMENT-BYTE-SIZE))]
         (PathData. count
                    (ByteBuffer/wrap buffer)
                    nil))

       :else
       (throw (java.lang.IllegalArgumentException. "invalid data provided")))

     :cljs
     (cond
       (instance? js/ArrayBuffer buffer)
       (let [size  (.-byteLength buffer)
             count (long (/ size SEGMENT-BYTE-SIZE))]
         (PathData. count
                    buffer
                    (js/DataView. buffer)
                    nil))

       (instance? js/DataView buffer)
       (let [dview  buffer
             buffer (.-buffer dview)
             size  (.-byteLength buffer)
             count (long (/ size SEGMENT-BYTE-SIZE))]
         (PathData. count buffer dview nil))

       (instance? js/Uint8Array buffer)
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
  (assert (check-segments segments))

  (let [total    (count segments)
        #?@(:cljs [buffer (new js/ArrayBuffer (* total SEGMENT-BYTE-SIZE))
                   dview  (new js/DataView buffer)]
            :clj  [buffer (ByteBuffer/allocate (* total SEGMENT-BYTE-SIZE))])]
    (loop [index 0]
      (when (< index total)
        (let [segment (nth segments index)
              offset  (* index SEGMENT-BYTE-SIZE)]
          (case (get segment :command)
            :move-to
            (let [params (get segment :params)
                  x      (float (get params :x))
                  y      (float (get params :y))]
              #?(:clj  (.putShort buffer (int offset) (short 1))
                 :cljs (.setInt16 dview offset 1))
              #?(:clj  (.putFloat buffer (+ offset 20) x)
                 :cljs (.setFloat32 dview (+ offset 20) x))
              #?(:clj  (.putFloat buffer (+ offset 24) y)
                 :cljs (.setFloat32 dview (+ offset 24) y)))

            :line-to
            (let [params (get segment :params)
                  x      (float (get params :x))
                  y      (float (get params :y))]
              #?(:clj  (.putShort buffer (int offset) (short 2))
                 :cljs (.setInt16 dview offset 2))
              #?(:clj  (.putFloat buffer (+ offset 20) x)
                 :cljs (.setFloat32 dview (+ offset 20) x))
              #?(:clj  (.putFloat buffer (+ offset 24) y)
                 :cljs (.setFloat32 dview (+ offset 24) y)))

            :curve-to
            (let [params (get segment :params)
                  x      (float (get params :x))
                  y      (float (get params :y))
                  c1x    (float (get params :c1x x))
                  c1y    (float (get params :c1y y))
                  c2x    (float (get params :c2x x))
                  c2y    (float (get params :c2y y))]

              #?(:clj  (.putShort buffer (int offset) (short 3))
                 :cljs (.setInt16 dview offset 3))
              #?(:clj  (.putFloat buffer (+ offset 4) c1x)
                 :cljs (.setFloat32 dview (+ offset 4) c1x))
              #?(:clj  (.putFloat buffer (+ offset 8) c1y)
                 :cljs (.setFloat32 dview (+ offset 8) c1y))
              #?(:clj  (.putFloat buffer (+ offset 12) c2x)
                 :cljs (.setFloat32 dview (+ offset 12) c2x))
              #?(:clj  (.putFloat buffer (+ offset 16) c2y)
                 :cljs (.setFloat32 dview (+ offset 16) c2y))
              #?(:clj  (.putFloat buffer (+ offset 20) x)
                 :cljs (.setFloat32 dview (+ offset 20) x))
              #?(:clj  (.putFloat buffer (+ offset 24) y)
                 :cljs (.setFloat32 dview (+ offset 24) y)))

            :close-path
            #?(:clj  (.putShort buffer (int offset) (short 4))
               :cljs (.setInt16 dview offset 4)))
          (recur (inc index)))))

    #?(:cljs (from-bytes dview)
       :clj  (from-bytes buffer))))

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
           #?(:cljs (js/Uint8Array. buffer)
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
              (from-bytes (ByteBuffer/wrap bytes))))}))

