;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.impl
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:cljs [app.common.data.macros :as dm])
   #?(:cljs [app.common.geom.rect :as grc])
   #?(:cljs [cuerdas.core :as str])
   [app.common.record :as cr]
   [app.common.transit :as t]
   [clojure.core :as c]))

(def enabled-wasm-ready-shape false)

#?(:cljs
   (do
     (def ArrayBuffer js/ArrayBuffer)
     (def Float32Array js/Float32Array)))

(cr/defrecord Shape [id name type x y width height rotation selrect points
                     transform transform-inverse parent-id frame-id flip-x flip-y])

(declare ^:private clone-f32-array)
(declare ^:private impl-assoc)
(declare ^:private impl-conj)
(declare ^:private impl-dissoc)
(declare ^:private read-selrect)
(declare ^:private write-selrect)

;; TODO: implement lazy MapEntry

#?(:cljs
   (deftype ShapeWithBuffer [buffer delegate]
     Object
     (toString [coll]
       (str "{" (str/join ", " (for [[k v] coll] (str k " " v))) "}"))

     (equiv [this other]
       (-equiv this other))

       ;; ICloneable
       ;; (-clone [_]
       ;;   (let [bf32 (clone-float32-array buffer)]
       ;;     (ShapeWithBuffer. bf32 delegate)))

     IWithMeta
     (-with-meta [_ meta]
       (ShapeWithBuffer. buffer (with-meta delegate meta)))

     IMeta
     (-meta [_] (meta delegate))

     ICollection
     (-conj [coll entry]
       (impl-conj coll entry))

     IEquiv
     (-equiv [coll other]
       (c/equiv-map coll other))

     IHash
     (-hash [coll] (hash (into {} coll)))

     ISequential

     ISeqable
     (-seq [coll]
       (cons (find coll :selrect)
             (seq delegate)))

     ICounted
     (-count [_]
       (+ 1 (count delegate)))

     ILookup
     (-lookup [coll k]
       (-lookup coll k nil))

     (-lookup [_ k not-found]
       (if (= k :selrect)
         (read-selrect buffer)
         (c/-lookup delegate k not-found)))

     IFind
     (-find [_ k]
       (if (= k :selrect)
         (c/MapEntry. k (read-selrect buffer) nil) ; Replace with lazy MapEntry
         (c/-find delegate k)))

     IAssociative
     (-assoc [coll k v]
       (impl-assoc coll k v))

     (-contains-key? [_ k]
       (or (= k :selrect)
           (contains? delegate k)))

     IMap
     (-dissoc [coll k]
       (impl-dissoc coll k))

     IFn
     (-invoke [coll k]
       (-lookup coll k))

     (-invoke [coll k not-found]
       (-lookup coll k not-found))

     IPrintWithWriter
     (-pr-writer [_ writer _]
       (-write writer (str "#penpot/shape " (:id delegate))))))

(defn shape?
  [o]
  #?(:clj (instance? Shape o)
     :cljs (or (instance? Shape o)
               (instance? ShapeWithBuffer o))))

;; --- SHAPE IMPL

#?(:cljs
   (defn- clone-f32-array
     [^Float32Array src]
     (let [copy (new Float32Array (.-length src))]
       (.set copy src)
       copy)))

#?(:cljs
   (defn- write-selrect
     "Write the selrect into the buffer"
     [data selrect]
     (assert (instance? Float32Array data) "expected instance of float32array")

     (aset data 0 (dm/get-prop selrect :x1))
     (aset data 1 (dm/get-prop selrect :y1))
     (aset data 2 (dm/get-prop selrect :x2))
     (aset data 3 (dm/get-prop selrect :y2))))

#?(:cljs
   (defn- read-selrect
     "Read selrect from internal buffer"
     [^Float32Array buffer]
     (let [x1 (aget buffer 0)
           y1 (aget buffer 1)
           x2 (aget buffer 2)
           y2 (aget buffer 3)]
       (grc/make-rect x1 y1
                      (- x2 x1)
                      (- y2 y1)))))

#?(:cljs
   (defn- impl-assoc
     [coll k v]
     (if (= k :selrect)
       (let [buffer (clone-f32-array (.-buffer coll))]
         (write-selrect buffer v)
         (ShapeWithBuffer. buffer (.-delegate coll)))

       (let [delegate  (.-delegate coll)
             delegate' (assoc delegate k v)]
         (if (identical? delegate' delegate)
           coll
           (let [buffer (clone-f32-array (.-buffer coll))]
             (ShapeWithBuffer. buffer delegate')))))))

#?(:cljs
   (defn- impl-dissoc
     [coll k]
     (let [delegate  (.-delegate coll)
           delegate' (dissoc delegate k)]
       (if (identical? delegate delegate')
         coll
         (let [buffer (clone-f32-array (.-buffer coll))]
           (ShapeWithBuffer. buffer delegate'))))))

#?(:cljs
   (defn- impl-conj
     [coll entry]
     (if (vector? entry)
       (-assoc coll (-nth entry 0) (-nth entry 1))
       (loop [ret coll es (seq entry)]
         (if (nil? es)
           ret
           (let [e (first es)]
             (if (vector? e)
               (recur (-assoc ret (-nth e 0) (-nth e 1))
                      (next es))
               (throw (js/Error. "conj on a map takes map entries or seqables of map entries")))))))))

(defn create-shape
  "Instanciate a shape from a map"
  [attrs]
  #?(:cljs
     (if enabled-wasm-ready-shape
       (let [selrect (:selrect attrs)
             buffer  (new Float32Array 4)]
         (write-selrect buffer selrect)
         (ShapeWithBuffer. buffer (dissoc attrs :selrect)))
       (map->Shape attrs))

     :clj (map->Shape attrs)))

;; --- SHAPE SERIALIZATION

(t/add-handlers!
 {:id "shape"
  :class Shape
  :wfn #(into {} %)
  :rfn create-shape})

#?(:cljs
   (t/add-handlers!
    {:id "shape"
     :class ShapeWithBuffer
     :wfn #(into {} %)
     :rfn create-shape}))

#?(:clj
   (fres/add-handlers!
    {:name "penpot/shape"
     :class Shape
     :wfn fres/write-map-like
     :rfn (comp create-shape fres/read-map-like)}))
