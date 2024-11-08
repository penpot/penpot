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
   [app.common.geom.matrix :as cgm]
   [app.common.record :as cr]
   [app.common.transit :as t]
   [clojure.core :as c]
   [okulary.core :as l]))

(defonce ^:dynamic *wasm-sync* true)
(defonce enabled-wasm-ready-shape false)
(defonce wasm-create-shape (constantly nil))
(defonce wasm-use-shape (constantly nil))
(defonce wasm-set-shape-selrect (constantly nil))
(defonce wasm-set-shape-transform (constantly nil))
(defonce wasm-set-shape-x (constantly nil))
(defonce wasm-set-shape-y (constantly nil))
(defonce wasm-set-shape-rotation (constantly nil))

(cr/defrecord Shape [id name type x y width height rotation selrect points
                     transform transform-inverse parent-id frame-id flip-x flip-y])

(declare ^:private impl-assoc)
(declare ^:private impl-conj)
(declare ^:private impl-dissoc)

;; TODO: implement lazy MapEntry

#?(:cljs
   (deftype ShapeProxy [delegate]
     Object
     (toString [coll]
       (str "{" (str/join ", " (for [[k v] coll] (str k " " v))) "}"))

     (equiv [this other]
       (-equiv this other))

     IWithMeta
     (-with-meta [_ meta]
       (ShapeProxy. (with-meta delegate meta)))

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
     (-seq [_]
       (c/-seq delegate))

     ICounted
     (-count [_]
       (+ 1 (count delegate)))

     ILookup
     (-lookup [coll k]
       (-lookup coll k nil))

     (-lookup [_ k not-found]
       (c/-lookup delegate k not-found))

     IFind
     (-find [_ k]
       (c/-find delegate k))

     IAssociative
     (-assoc [coll k v]
       (impl-assoc coll k v))

     (-contains-key? [_ k]
       (contains? delegate k))

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
               (instance? ShapeProxy o))))

;; --- SHAPE IMPL

#?(:cljs
   (defn- impl-assoc
     [coll k v]
     (when *wasm-sync*
       (wasm-use-shape (:id coll))
       (case k
         :x nil #_(wasm-set-shape-x v)
         :y nil #_(wasm-set-shape-y v)
         :selrect (wasm-set-shape-selrect v)
         :rotation (wasm-set-shape-rotation v)
         :transform (wasm-set-shape-transform v)
         nil))
     (let [delegate  (.-delegate ^ShapeProxy coll)
           delegate' (assoc delegate k v)]
       (if (identical? delegate' delegate)
         coll
         (ShapeProxy. delegate')))))

#?(:cljs
   (defn- impl-dissoc
     [coll k]
     (let [delegate  (.-delegate ^ShapeProxy coll)
           delegate' (dissoc delegate k)]
       (if (identical? delegate delegate')
         coll
         (ShapeProxy. delegate')))))

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
       (ShapeProxy. attrs)
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
     :class ShapeProxy
     :wfn #(into {} %)
     :rfn create-shape}))

#?(:clj
   (fres/add-handlers!
    {:name "penpot/shape"
     :class Shape
     :wfn fres/write-map-like
     :rfn (comp create-shape fres/read-map-like)}))
