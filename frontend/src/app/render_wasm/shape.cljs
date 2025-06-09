;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.shape
  (:require
   [app.common.data.macros :as dm]
   [app.common.transit :as t]
   [app.common.types.shape :as shape]
   [app.common.types.shape.layout :as ctl]
   [app.render-wasm.api :as api]
   [beicon.v2.core :as rx]
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

(defn set-wasm-single-attr!
  [shape k]
  (let [v (get shape k)]
    (case k
      :parent-id    (api/set-parent-id v)
      :type         (api/set-shape-type v)
      :bool-type    (api/set-shape-bool-type v)
      :selrect      (api/set-shape-selrect v)
      :show-content (if (= (:type shape) :frame)
                      (api/set-shape-clip-content (not v))
                      (api/set-shape-clip-content false))
      :rotation     (api/set-shape-rotation v)
      :transform    (api/set-shape-transform v)
      :fills        (into [] (api/set-shape-fills v))
      :strokes      (into [] (api/set-shape-strokes v))
      :blend-mode   (api/set-shape-blend-mode v)
      :opacity      (api/set-shape-opacity v)
      :hidden       (api/set-shape-hidden v)
      :shapes       (api/set-shape-children v)
      :blur         (api/set-shape-blur v)
      :shadow       (api/set-shape-shadows v)
      :constraints-h (api/set-constraints-h v)
      :constraints-v (api/set-constraints-v v)

      (:r1 :r2 :r3 :r4)
      (api/set-shape-corners [(dm/get-prop shape :r1)
                              (dm/get-prop shape :r2)
                              (dm/get-prop shape :r3)
                              (dm/get-prop shape :r4)])

      :svg-attrs
      (when (= (:type shape) :path)
        (api/set-shape-path-attrs v))

      :masked-group
      (when (and (= (:type shape) :group) (:masked-group shape))
        (api/set-masked (:masked-group shape)))

      :content
      (cond
        (or (= (:type shape) :path)
            (= (:type shape) :bool))
        (api/set-shape-path-content v)

        (= (:type shape) :svg-raw)
        (api/set-shape-svg-raw-content (api/get-static-markup shape))

        (= (:type shape) :text)
        (api/set-shape-text v))

      :grow-type
      (api/set-shape-grow-type v)

      (:layout-item-margin
       :layout-item-margin-type
       :layout-item-h-sizing
       :layout-item-v-sizing
       :layout-item-max-h
       :layout-item-min-h
       :layout-item-max-w
       :layout-item-min-w
       :layout-item-absolute
       :layout-item-z-index)
      (api/set-layout-child shape)

      :layout-grid-rows
      (api/set-grid-layout-rows v)

      :layout-grid-columns
      (api/set-grid-layout-columns v)

      :layout-grid-cells
      (api/set-grid-layout-cells v)

      (:layout
       :layout-flex-dir
       :layout-gap-type
       :layout-gap
       :layout-align-items
       :layout-align-content
       :layout-justify-items
       :layout-justify-content
       :layout-wrap-type
       :layout-padding-type
       :layout-padding)
      (cond
        (ctl/grid-layout? shape)
        (api/set-grid-layout-data shape)

        (ctl/flex-layout? shape)
        (api/set-flex-layout shape))

      nil)))

(defn set-wasm-multi-attrs!
  [shape properties]
  (api/use-shape (:id shape))
  (let [pending
        (->> properties
             (mapcat #(set-wasm-single-attr! shape %)))]
    (if (and pending (seq pending))
      (->> (rx/from pending)
           (rx/mapcat identity)
           (rx/reduce conj [])
           (rx/subs!
            (fn [_]
              (api/update-shape-tiles)
              (api/clear-drawing-cache)
              (api/request-render "set-wasm-attrs-pending"))))
      (do
        (api/update-shape-tiles)
        (api/request-render "set-wasm-attrs")))))

(defn set-wasm-attrs!
  [shape k v]
  (let [shape (assoc shape k v)]
    (api/use-shape (:id shape))
    (let [pending (set-wasm-single-attr! shape k)]
      ;; TODO: set-wasm-attrs is called twice with every set
      (if (and pending (seq pending))
        (->> (rx/from pending)
             (rx/mapcat identity)
             (rx/reduce conj [])
             (rx/subs!
              (fn [_]
                (api/update-shape-tiles)
                (api/clear-drawing-cache)
                (api/request-render "set-wasm-attrs-pending"))))
        (do
          (api/update-shape-tiles)
          (api/request-render "set-wasm-attrs"))))))

(defn- impl-assoc
  [self k v]
  (when ^boolean shape/*wasm-sync*
    (binding [shape/*wasm-sync* false]
      (set-wasm-attrs! self k v)))

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
  (when ^boolean shape/*wasm-sync*
    (binding [shape/*wasm-sync* false]
      (set-wasm-attrs! self k nil)))

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
