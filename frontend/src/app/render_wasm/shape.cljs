;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.shape
  (:require
   [app.common.transit :as t]
   [app.common.types.shape :as shape]
   [app.render-wasm.api :as api]
   [clojure.core :as c]
   [cuerdas.core :as str]))

(declare ^:private impl-assoc)
(declare ^:private impl-conj)
(declare ^:private impl-dissoc)

(deftype ShapeProxy [delegate]
  Object
  (toString [coll]
    (str "{" (str/join ", " (for [[k v] coll] (str k " " v))) "}"))

  (equiv [this other]
    (-equiv this other))

  ;; Marker protocol
  shape/IShape

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
    (-write writer (str "#penpot/shape " (:id delegate)))))

;; --- SHAPE IMPL

(defn- impl-assoc
  [self k v]
  (when ^boolean shape/*wasm-sync*
    (api/use-shape (:id self))
    (case k
      :selrect    (api/set-shape-selrect v)
      :rotation   (api/set-shape-rotation v)
      :transform  (api/set-shape-transform v)
      :fills      (api/set-shape-fills v)
      :blend-mode (api/set-shape-blend-mode v)
      :shapes     (api/set-shape-children v)
      nil)
    ;; when something synced with wasm
    ;; is modified, we need to request
    ;; a new render.
    (api/request-render))
  (let [delegate  (.-delegate ^ShapeProxy self)
        delegate' (assoc delegate k v)]
    (if (identical? delegate' delegate)
      self
      (ShapeProxy. delegate'))))

(defn- impl-dissoc
  [self k]
  (let [delegate  (.-delegate ^ShapeProxy self)
        delegate' (dissoc delegate k)]
    (if (identical? delegate delegate')
      self
      (ShapeProxy. delegate'))))

(defn- impl-conj
  [self entry]
  (if (vector? entry)
    (-assoc self (-nth entry 0) (-nth entry 1))
    (loop [ret self es (seq entry)]
      (if (nil? es)
        ret
        (let [e (first es)]
          (if (vector? e)
            (recur (-assoc ret (-nth e 0) (-nth e 1))
                   (next es))
            (throw (js/Error. "conj on a map takes map entries or seqables of map entries"))))))))

(defn create-shape
  "Instanciate a shape from a map"
  [attrs]
  (ShapeProxy. attrs))

(t/add-handlers!
 ;; We only add a write handler, read handler uses the dynamic dispatch
 {:id "shape"
  :class ShapeProxy
  :wfn #(into {} %)})
