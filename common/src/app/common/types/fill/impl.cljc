;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.fill.impl
  (:require
   #?(:clj [clojure.data.json :as json])
   #?(:cljs [app.common.weak-map :as weak-map])
   [app.common.buffer :as buf]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.transit :as t]))

;; FIXME: Get these from the wasm module, and tweak the values
;; (we'd probably want 12 stops at most)
(def ^:const MAX-GRADIENT-STOPS 16)
(def ^:const MAX-FILLS 8)

(def ^:const GRADIENT-STOP-SIZE 8)
(def ^:const GRADIENT-BYTE-SIZE 156)
(def ^:const SOLID-BYTE-SIZE 4)
(def ^:const IMAGE-BYTE-SIZE 28)
(def ^:const METADATA-BYTE-SIZE 36)
(def ^:const FILL-BYTE-SIZE
  (+ 4 (mth/max GRADIENT-BYTE-SIZE
                IMAGE-BYTE-SIZE
                SOLID-BYTE-SIZE)))

(def ^:private xf:take-stops
  (take MAX-GRADIENT-STOPS))

(def ^:private xf:take-fills
  (take MAX-FILLS))

(defn- hex->rgb
  "Encode an hex string as rgb (int32)"
  [hex]
  (let [hex (subs hex 1)]
    #?(:clj (Integer/parseInt hex 16)
       :cljs (js/parseInt hex 16))))

(defn- rgb->rgba
  "Use the first 2 bytes of in32 for encode the alpha channel"
  [n alpha]
  (let [result (mth/floor (* alpha 0xff))
        result (unchecked-int result)
        result (bit-shift-left result 24)
        result (bit-or result n)]
    result))

(defn- get-color-hex
  [n]
  (let [n (bit-and n 0x00ffffff)
        n #?(:clj n :cljs (.toString n 16))]
    (dm/str "#" #?(:clj (String/format "%06x" (into-array Object [n]))
                   :cljs (.padStart n 6 "0")))))

(defn- get-color-alpha
  [rgb]
  (let [n (bit-and rgb 0xff000000)
        n (unsigned-bit-shift-right n 24)]
    (mth/precision (/ (float n) 0xff) 2)))

(defn- write-solid-fill
  [offset buffer color alpha]
  (buf/write-byte buffer (+ offset 0) 0x00)
  (buf/write-int  buffer (+ offset 4)
                  (-> (hex->rgb color)
                      (rgb->rgba alpha)))
  (+ offset FILL-BYTE-SIZE))

(defn- write-gradient-fill
  [offset buffer gradient opacity]
  (let [start-x (:start-x gradient)
        start-y (:start-y gradient)
        end-x   (:end-x gradient)
        end-y   (:end-y gradient)
        width   (:width gradient 0)
        stops   (into [] xf:take-stops (:stops gradient))
        type    (if (= (:type gradient) :linear)
                  0x01
                  0x02)]

    (buf/write-byte  buffer (+ offset 0)  type)
    (buf/write-float buffer (+ offset 4)  start-x)
    (buf/write-float buffer (+ offset 8)  start-y)
    (buf/write-float buffer (+ offset 12) end-x)
    (buf/write-float buffer (+ offset 16) end-y)
    (buf/write-float buffer (+ offset 20) opacity)
    (buf/write-float buffer (+ offset 24) width)
    (buf/write-byte  buffer (+ offset 28) (count stops))

    (loop [stops   (seq stops)
           offset' (+ offset 32)]
      (if-let [stop (first stops)]
        (let [color (-> (hex->rgb (:color stop))
                        (rgb->rgba (:opacity stop 1)))]
          ;; NOTE: we write the color as signed integer but on rust
          ;; side it will be read as unsigned, on the end the binary
          ;; repr of the data is the same independently on how it is
          ;; interpreted
          (buf/write-int   buffer (+ offset' 0) color)
          (buf/write-float buffer (+ offset' 4) (:offset stop))
          (recur (rest stops)
                 (+ offset' GRADIENT-STOP-SIZE)))
        (+ offset FILL-BYTE-SIZE)))))

(defn- write-image-fill
  [offset buffer opacity image]
  (let [image-id (get image :id)
        image-width (get image :width)
        image-height (get image :height)]
    (buf/write-byte  buffer (+ offset  0) 0x03)
    (buf/write-uuid  buffer (+ offset  4) image-id)
    (buf/write-float buffer (+ offset 20) opacity)
    (buf/write-int   buffer (+ offset 24) image-width)
    (buf/write-int   buffer (+ offset 28) image-height)
    (+ offset FILL-BYTE-SIZE)))

(defn- write-metadata
  [offset buffer fill]
  (let [ref-id   (:fill-color-ref-id fill)
        ref-file (:fill-color-ref-file fill)
        mtype    (dm/get-in fill [:fill-image :mtype])]

    (when mtype
      (let [val (case mtype
                  "image/jpeg" 0x01
                  "image/png"  0x02
                  "image/gif"  0x03
                  "image/webp"  0x04
                  "image/svg+xml" 0x05)]
        (buf/write-short buffer (+ offset 2) val)))

    (if (and (some? ref-file)
             (some? ref-id))
      (do
        (buf/write-byte buffer (+ offset 0) 0x01)
        (buf/write-uuid buffer (+ offset 4) ref-file)
        (buf/write-uuid buffer (+ offset 20) ref-id))
      (do
        (buf/write-byte buffer (+ offset 0) 0x00)))))

(defn- read-stop
  [buffer offset]
  (let [rgba (buf/read-int buffer (+ offset 0))
        soff (buf/read-float buffer (+ offset 4))]
    {:color (get-color-hex rgba)
     :opacity (get-color-alpha rgba)
     :offset (mth/precision soff 2)}))

(defn- read-fill
  "Read segment from binary buffer at specified index"
  [dbuffer mbuffer index]
  (let [doffset (+ 4 (* index FILL-BYTE-SIZE))
        moffset (* index METADATA-BYTE-SIZE)
        type    (buf/read-byte dbuffer doffset)
        refs?   (buf/read-bool mbuffer (+ moffset 0))
        fill    (case type
                  0
                  (let [rgba (buf/read-int dbuffer (+ doffset 4))]
                    {:fill-color (get-color-hex rgba)
                     :fill-opacity (get-color-alpha rgba)})

                  (1 2)
                  (let [start-x (buf/read-float dbuffer (+ doffset 4))
                        start-y (buf/read-float dbuffer (+ doffset 8))
                        end-x   (buf/read-float dbuffer (+ doffset 12))
                        end-y   (buf/read-float dbuffer (+ doffset 16))
                        alpha   (buf/read-float dbuffer (+ doffset 20))
                        width   (buf/read-float dbuffer (+ doffset 24))
                        stops   (buf/read-byte  dbuffer (+ doffset 28))
                        type    (if (= type 1)
                                  :linear
                                  :radial)
                        stops   (loop [index  0
                                       result []]
                                  (if (< index stops)
                                    (recur (inc index)
                                           (conj result (read-stop dbuffer (+ doffset 32 (* GRADIENT-STOP-SIZE index)))))
                                    result))]

                    {:fill-opacity alpha
                     :fill-color-gradient {:start-x start-x
                                           :start-y start-y
                                           :end-x end-x
                                           :end-y end-y
                                           :width width
                                           :stops stops
                                           :type type}})

                  3
                  (let [id     (buf/read-uuid  dbuffer (+ doffset 4))
                        alpha  (buf/read-float dbuffer (+ doffset 20))
                        width  (buf/read-int   dbuffer (+ doffset 24))
                        height (buf/read-int   dbuffer (+ doffset 28))
                        mtype  (buf/read-short mbuffer (+ moffset 2))
                        mtype  (case mtype
                                 0x01 "image/jpeg"
                                 0x02 "image/png"
                                 0x03 "image/gif"
                                 0x04 "image/webp"
                                 0x05 "image/svg+xml")]
                    {:fill-opacity alpha
                     :fill-image {:id id
                                  :width width
                                  :height height
                                  :mtype mtype
                                  ;; FIXME: we are not encodign the name, looks useless
                                  :name "sample"}}))]

    (if refs?
      (let [ref-file (buf/read-uuid mbuffer (+ moffset 4))
            ref-id   (buf/read-uuid mbuffer (+ moffset 20))]
        (-> fill
            (assoc :fill-color-ref-id ref-id)
            (assoc :fill-color-ref-file ref-file)))
      fill)))

(declare from-plain)

#?(:clj
   (deftype Fills [size dbuffer mbuffer ^:unsynchronized-mutable hash]
     Object
     (equals [_ other]
       (if (instance? Fills other)
         (and (buf/equals? dbuffer (.-dbuffer ^Fills other))
              (buf/equals? mbuffer (.-mbuffer ^Fills other)))
         false))

     json/JSONWriter
     (-write [this writter options]
       (json/-write (vec this) writter options))

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
              (cons (read-fill dbuffer mbuffer i)
                    (lazy-seq (next-seq (inc i))))))
          0)))

     clojure.lang.IReduceInit
     (reduce [_ f start]
       (loop [index  0
              result start]
         (if (< index size)
           (let [result (f result (read-fill dbuffer mbuffer index))]
             (if (reduced? result)
               @result
               (recur (inc index) result)))
           result)))

     clojure.lang.Indexed
     (nth [_ i]
       (if (d/in-range? size i)
         (read-fill dbuffer mbuffer i)
         nil))

     (nth [_ i default]
       (if (d/in-range? size i)
         (read-fill dbuffer mbuffer i)
         default))

     clojure.lang.Counted
     (count [_] size))

   :cljs
   #_:clj-kondo/ignore
   (deftype Fills [size dbuffer mbuffer cache ^:mutable __hash]
     cljs.core/ISequential
     cljs.core/IEquiv
     (-equiv [this other]
       (if (instance? Fills other)
         (and ^boolean (buf/equals? (.-dbuffer ^Fills other) dbuffer)
              ^boolean (buf/equals? (.-mbuffer ^Fills other) mbuffer))
         false))

     cljs.core/IEncodeJS
     (-clj->js [this]
       (clj->js (vec this)))

     ;; cljs.core/APersistentVector
     cljs.core/IAssociative
     (-assoc [coll k v]
       (if (number? k)
         (-> (vec coll)
             (assoc k v)
             (from-plain))
         (throw (js/Error. "Vector's key for assoc must be a number."))))

     (-contains-key? [coll k]
       (if (integer? k)
         (and (<= 0 k) (< k size))
         false))

     cljs.core/IReduce
     (-reduce [_ f]
       (loop [index  1
              result (if (pos? size)
                       (read-fill dbuffer mbuffer 0)
                       nil)]
         (if (< index size)
           (let [result (f result (read-fill dbuffer mbuffer index))]
             (if (reduced? result)
               @result
               (recur (inc index) result)))
           result)))

     (-reduce [_ f start]
       (loop [index  0
              result start]
         (if (< index size)
           (let [result (f result (read-fill dbuffer mbuffer index))]
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
         (read-fill dbuffer mbuffer i)
         nil))

     (-nth [_ i default]
       (if (d/in-range? i size)
         (read-fill dbuffer mbuffer i)
         default))

     cljs.core/ISeqable
     (-seq [this]
       (when (pos? size)
         ((fn next-seq [i]
            (when (< i size)
              (cons (read-fill dbuffer mbuffer i)
                    (lazy-seq (next-seq (inc i))))))
          0)))))

(defn from-plain
  [fills]
  (let [fills   (into [] xf:take-fills fills)
        total   (count fills)
        dbuffer (buf/allocate (+ 4 (* MAX-FILLS FILL-BYTE-SIZE)))
        mbuffer (buf/allocate (* total METADATA-BYTE-SIZE))]

    (buf/write-byte dbuffer 0 total)

    (loop [index 0]
      (when (< index total)
        (let [fill     (nth fills index)
              doffset  (+ 4 (* index FILL-BYTE-SIZE))
              moffset  (* index METADATA-BYTE-SIZE)
              opacity  (get fill :fill-opacity 1)]

          (if-let [color (get fill :fill-color)]
            (do
              (write-solid-fill doffset dbuffer color opacity)
              (write-metadata moffset mbuffer fill)
              (recur (inc index)))
            (if-let [gradient (get fill :fill-color-gradient)]
              (do
                (write-gradient-fill doffset dbuffer gradient opacity)
                (write-metadata moffset mbuffer fill)
                (recur (inc index)))
              (if-let [image (get fill :fill-image)]
                (do
                  (write-image-fill doffset dbuffer opacity image)
                  (write-metadata moffset mbuffer fill)
                  (recur (inc index)))
                (recur (inc index))))))))

    #?(:cljs (Fills. total dbuffer mbuffer (weak-map/create) nil)
       :clj  (Fills. total dbuffer mbuffer nil))))

(defn fills?
  [o]
  (instance? Fills o))

(t/add-handlers!
 {:id "penpot/fills"
  :class Fills
  :wfn (fn [^Fills fills]
         (vec fills))
  :rfn #?(:cljs from-plain
          :clj identity)})
