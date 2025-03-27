;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.path
  (:require
   [app.common.schema :as sm])
  (:import
   #?(:cljs [goog.string StringBuffer]
      :clj  [java.nio ByteBuffer])))

#?(:clj (set! *warn-on-reflection* true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA: PLAIN FORMAT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:line-to-segment
  [:map
   [:command [:= :line-to]]
   [:params
    [:map
     [:x ::sm/safe-number]
     [:y ::sm/safe-number]]]])

(def schema:close-path-segment
  [:map
   [:command [:= :close-path]]])

(def schema:move-to-segment
  [:map
   [:command [:= :move-to]]
   [:params
    [:map
     [:x ::sm/safe-number]
     [:y ::sm/safe-number]]]])

(def schema:curve-to-segment
  [:map
   [:command [:= :curve-to]]
   [:params
    [:map
     [:x ::sm/safe-number]
     [:y ::sm/safe-number]
     [:c1x ::sm/safe-number]
     [:c1y ::sm/safe-number]
     [:c2x ::sm/safe-number]
     [:c2y ::sm/safe-number]]]])

(def schema:path-segment
  [:multi {:title "PathSegment"
           :dispatch :command
           :decode/json #(update % :command keyword)}
   [:line-to schema:line-to-segment]
   [:close-path schema:close-path-segment]
   [:move-to schema:move-to-segment]
   [:curve-to schema:curve-to-segment]])

(def schema:path-content
  [:vector schema:path-segment])

(def check-path-content
  (sm/check-fn schema:path-content))

(sm/register! ::segment schema:path-segment)
(sm/register! ::content schema:path-content)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TYPE: PATH-DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const SEGMENT-BYTE-SIZE 28)

(defprotocol IPathData
  (-write-to [_ buffer offset] "write the content to the specified buffer"))

(defrecord PathSegment [command params])

(defn- get-path-string
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
  [buffer index]
  (let [offset (* index SEGMENT-BYTE-SIZE)
        type   #?(:clj  (.getShort ^ByteBuffer buffer offset)
                  :cljs (.getInt16 buffer offset))]
    (case (long type)
      1 (let [x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                   :cljs (.getFloat32 buffer (+ offset 20)))
              y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                   :cljs (.getFloat32 buffer (+ offset 24)))]
          (->PathSegment :move-to {:x x :y y}))

      2 (let [x #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 20))
                   :cljs (.getFloat32 buffer (+ offset 20)))
              y #?(:clj  (.getFloat ^ByteBuffer buffer (+ offset 24))
                   :cljs (.getFloat32 buffer (+ offset 24)))]
          (->PathSegment :line-to {:x x :y y}))

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

          (->PathSegment :curve-to {:x x :y y :c1x c1x :c1y c1y :c2x c2x :c2y c2y}))

      4 (->PathSegment :close-path {}))))

(defn- in-range?
  [size i]
  (and (< i size) (>= i 0)))

#?(:clj
   (deftype PathData [size buffer]
     Object
     (toString [_]
       (get-path-string buffer size))

     clojure.lang.Sequential
     clojure.lang.IPersistentCollection

     (empty [_]
       (throw (ex-info "not implemented" {})))
     (equiv [_ other]
       (if (instance? PathData other)
         (.equals ^ByteBuffer buffer (.-buffer ^PathData other))
         false))

     (seq [this]
       (when (pos? size)
         (->> (range size)
              (map (fn [i] (nth this i))))))

     (cons [_ _val]
       (throw (ex-info "not implemented" {})))

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
     (count [_] size))

   :cljs
   (deftype PathData [size buffer dview]
     Object
     (toString [_]
       (get-path-string dview size))

     IPathData
     (-write-to [_ into-buffer offset]
       (assert (instance? js/ArrayBuffer into-buffer) "expected an instance of Uint32Array")
       (let [size (.-byteLength buffer)
             mem  (js/Uint32Array. into-buffer offset size)]
         (.set mem (js/Uint32Array. buffer))))

     cljs.core/ISequential
     cljs.core/IEquiv
     (-equiv [_ other]
       (if (instance? PathData other)
         (let [obuffer (.-buffer other)
               osize   (.-byteLength obuffer)
               csize   (.-byteLength buffer)]
           (if (= osize csize)
             (let [cb (js/Uint32Array. buffer)
                   ob (js/Uint32Array. obuffer)]
               (loop [i 0]
                 (if (< i osize)
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
     (-hash [_]
       (throw (ex-info "not-implemented" {})))

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
              (map (fn [i] (cljs.core/-nth this i))))))))

(defn- from-bytes
  [buffer]
  #?(:clj
     (cond
       (instance? ByteBuffer buffer)
       (let [size  (.capacity ^ByteBuffer buffer)
             count (long (/ size SEGMENT-BYTE-SIZE))]
         (PathData. count buffer))

       (bytes? buffer)
       (let [size  (alength ^bytes buffer)
             count (long (/ size SEGMENT-BYTE-SIZE))]
         (PathData. count
                    (ByteBuffer/wrap buffer)))

       :else
       (throw (java.lang.IllegalArgumentException. "invalid data provided")))

     :cljs
     (cond
       (instance? js/ArrayBuffer buffer)
       (let [size  (.-byteLength buffer)
             count (long (/ size SEGMENT-BYTE-SIZE))]
         (PathData. count
                    buffer
                    (js/DataView. buffer)))

       (instance? js/DataView buffer)
       (let [dview  buffer
             buffer (.-buffer dview)
             size  (.-byteLength buffer)
             count (long (/ size SEGMENT-BYTE-SIZE))]
         (PathData. count buffer dview))

       :else
       (throw (js/Error. "invalid data provided")))))

;; FIXME: consider implementing with reduce
;; FIXME: consider ensure fixed precision for avoid doing it on formatting

(defn- from-plain
  "Create a PathData instance from plain data structures"
  [content]
  (assert (check-path-content content))

  (let [content (vec content)
        total   (count content)
        #?@(:cljs [buffer (new js/ArrayBuffer (* total SEGMENT-BYTE-SIZE))
                   dview  (new js/DataView buffer)]
            :clj  [buffer (ByteBuffer/allocate (* total SEGMENT-BYTE-SIZE))])]
    (loop [index 0]
      (when (< index total)
        (let [segment (nth content index)
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
    (instance? PathData data)
    data

    (sequential? data)
    (from-plain data)

    :else
    (from-bytes data)))
