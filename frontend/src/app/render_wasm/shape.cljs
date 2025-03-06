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

(defn map-entry
  [k v]
  (cljs.core/MapEntry. k v nil))

(deftype ShapeProxy [id type delegate]
  Object
  (toString [coll]
    (str "{" (str/join ", " (for [[k v] coll] (str k " " v))) "}"))

  (equiv [this other]
    (-equiv this other))

  ;; Marker protocol
  shape/IShape

  IWithMeta
  (-with-meta [_ meta]
    (ShapeProxy. id type (with-meta delegate meta)))

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
    (cons (map-entry :id id)
          (cons (map-entry :type type)
                (c/-seq delegate))))

  ICounted
  (-count [_]
    (+ 1 (count delegate)))

  ILookup
  (-lookup [coll k]
    (-lookup coll k nil))

  (-lookup [_ k not-found]
    (case k
      :id id
      :type type
      (c/-lookup delegate k not-found)))

  IFind
  (-find [_ k]
    (case k
      :id
      (map-entry :id id)
      :type
      (map-entry :type type)
      (c/-find delegate k)))

  IAssociative
  (-assoc [coll k v]
    (impl-assoc coll k v))

  (-contains-key? [_ k]
    (or (= k :id)
        (= k :type)
        (contains? delegate k)))

  IMap
  (-dissoc [coll k]
    (impl-dissoc coll k))

  IFn
  (-invoke [coll k]
    (-lookup coll k nil))

  (-invoke [coll k not-found]
    (-lookup coll k not-found))

  IPrintWithWriter
  (-pr-writer [_ writer _]
    (-write writer (str "#penpot/shape " (:id delegate)))))

;; --- SHAPE IMPL

(defn- set-wasm-attrs
  [self k v]
  (when ^boolean shape/*wasm-sync*
    (api/use-shape (:id self))
    (case k
      :parent-id    (api/set-parent-id v)
      :type         (api/set-shape-type v)
      :bool-type    (api/set-shape-bool-type v)
      :bool-content (api/set-shape-bool-content v)
      :selrect      (api/set-shape-selrect v)
      :show-content (if (= (:type self) :frame)
                      (api/set-shape-clip-content (not v))
                      (api/set-shape-clip-content false))
      :rotation     (api/set-shape-rotation v)
      :transform    (api/set-shape-transform v)
      :fills        (api/set-shape-fills v)
      :strokes      (api/set-shape-strokes v)
      :blend-mode   (api/set-shape-blend-mode v)
      :opacity      (api/set-shape-opacity v)
      :hidden       (api/set-shape-hidden v)
      :shapes       (api/set-shape-children v)
      :blur         (api/set-shape-blur v)
      :constraints-h (api/set-constraints-h v)
      :constraints-v (api/set-constraints-v v)

      :svg-attrs    (when (= (:type self) :path)
                      (api/set-shape-path-attrs v))
      :masked-group (when (and (= (:type self) :group) (:masked-group self))
                      (api/set-masked (:masked-group self)))
      :content      (cond
                      (= (:type self) :path)
                      (api/set-shape-path-content v)

                      (= (:type self) :svg-raw)
                      (api/set-shape-svg-raw-content (api/get-static-markup self)))
      nil)
    ;; when something synced with wasm
    ;; is modified, we need to request
    ;; a new render.
    (api/clear-cache)
    (api/request-render "set-wasm-attrs")))

(defn- impl-assoc
  [self k v]
  (set-wasm-attrs self k v)
  (case k
    :id
    (ShapeProxy. v
                 (.-type ^ShapeProxy self)
                 (.-delegate ^ShapeProxy self))
    :type
    (ShapeProxy. (.-id ^ShapeProxy self)
                 v
                 (.-delegate ^ShapeProxy self))

    (let [delegate  (.-delegate ^ShapeProxy self)
          delegate' (assoc delegate k v)]
      (if (identical? delegate' delegate)
        self
        (ShapeProxy. (.-id ^ShapeProxy self)
                     (.-type ^ShapeProxy self)
                     delegate')))))

(defn- impl-dissoc
  [self k]
  (set-wasm-attrs self k nil)
  (case k
    :id
    (ShapeProxy. nil
                 (.-type ^ShapeProxy self)
                 (.-delegate ^ShapeProxy self))
    :type
    (ShapeProxy. (.-id ^ShapeProxy self)
                 nil
                 (.-delegate ^ShapeProxy self))
    (let [delegate  (.-delegate ^ShapeProxy self)
          delegate' (dissoc delegate k)]
      (if (identical? delegate delegate')
        self
        (ShapeProxy. (.-id ^ShapeProxy self)
                     (.-type ^ShapeProxy self)
                     delegate')))))

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
  (ShapeProxy. (:id attrs)
               (:type attrs)
               (dissoc attrs :id :type)))

(t/add-handlers!
 ;; We only add a write handler, read handler uses the dynamic dispatch
 {:id "shape"
  :class ShapeProxy
  :wfn #(into {} %)})
