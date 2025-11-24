;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.shape
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.transit :as t]
   [app.common.types.shape :as shape]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.render-wasm.api :as api]
   [app.render-wasm.svg-fills :as svg-fills]
   [app.render-wasm.wasm :as wasm]
   [beicon.v2.core :as rx]
   [cljs.core :as c]
   [cuerdas.core :as str]))

(declare ^:private impl-assoc)
(declare ^:private impl-conj)
(declare ^:private impl-dissoc)

(defn shape-in-current-page?
  "Check if a shape is in the current page by looking up the current page objects"
  [shape-id]
  (let [objects (deref refs/workspace-page-objects)]
    (contains? objects shape-id)))

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

  c/IWithMeta
  (-with-meta [_ meta]
    (ShapeProxy. id type (with-meta delegate meta)))

  c/IMeta
  (-meta [_] (meta delegate))

  c/ICollection
  (-conj [coll entry]
    (impl-conj coll entry))

  c/IEmptyableCollection
  (-empty [_]
    (ShapeProxy. nil nil nil))

  c/IEquiv
  (-equiv [coll other]
    (c/equiv-map coll other))

  c/IHash
  (-hash [coll]
    (hash (into {} coll)))

  c/ISequential
  c/ISeqable
  (-seq [_]
    (cons (map-entry :id id)
          (cons (map-entry :type type)
                (c/-seq delegate))))

  c/ICounted
  (-count [_]
    (+ 1 (count delegate)))

  c/ILookup
  (-lookup [coll k]
    (-lookup coll k nil))

  (-lookup [_ k not-found]
    (case k
      :id id
      :type type
      (c/-lookup delegate k not-found)))

  c/IFind
  (-find [_ k]
    (case k
      :id
      (map-entry :id id)
      :type
      (map-entry :type type)
      (c/-find delegate k)))

  c/IAssociative
  (-assoc [coll k v]
    (impl-assoc coll k v))

  (-contains-key? [_ k]
    (or (= k :id)
        (= k :type)
        (contains? delegate k)))

  c/IMap
  (-dissoc [coll k]
    (impl-dissoc coll k))

  c/IFn
  (-invoke [coll k]
    (-lookup coll k nil))

  (-invoke [coll k not-found]
    (-lookup coll k not-found))

  c/IPrintWithWriter
  (-pr-writer [_ writer _]
    (-write writer (str "#penpot/shape " (:id delegate)))))

;; --- SHAPE IMPL
;; When an attribute is sent to WASM it could still be pending some side operations
;; for example: font loading when changing a text, this is an async operation that will
;; resolve eventually.
;; The `set-wasm-attr!` can return a list of callbacks to be executed in a second pass.
(defn- set-wasm-attr!
  [shape k]
  (when wasm/context-initialized?
    (let [v  (get shape k)
          id (get shape :id)]
      (case k
        :parent-id
        (api/set-parent-id v)

        :type
        (do
          (api/set-shape-type v)
          (when (or (= v :path) (= v :bool))
            (api/set-shape-path-content (:content shape))))

        :bool-type
        (api/set-shape-bool-type v)

        :selrect
        (do
          (api/set-shape-selrect v)
          (when (cfh/svg-raw-shape? shape)
            (api/set-shape-svg-raw-content (api/get-static-markup shape))))

        :show-content
        (if (cfh/frame-shape? shape)
          (api/set-shape-clip-content (not v))
          (api/set-shape-clip-content false))

        :rotation
        (api/set-shape-rotation v)

        :transform
        (api/set-shape-transform v)

        :fills
        (let [fills (svg-fills/resolve-shape-fills shape)]
          (into [] (api/set-shape-fills id fills false)))

        :strokes
        (into [] (api/set-shape-strokes id v false))

        :blend-mode
        (api/set-shape-blend-mode v)

        :opacity
        (api/set-shape-opacity v)

        :hidden
        (api/set-shape-hidden v)

        :shapes
        (api/set-shape-children v)

        :blur
        (api/set-shape-blur v)

        :shadow
        (api/set-shape-shadows v)

        :constraints-h
        (api/set-constraints-h v)

        :constraints-v
        (api/set-constraints-v v)

        :r1
        (api/set-shape-corners
         [v
          (dm/get-prop shape :r2)
          (dm/get-prop shape :r3)
          (dm/get-prop shape :r4)])

        :r2
        (api/set-shape-corners
         [(dm/get-prop shape :r1)
          v
          (dm/get-prop shape :r3)
          (dm/get-prop shape :r4)])

        :r3
        (api/set-shape-corners
         [(dm/get-prop shape :r1)
          (dm/get-prop shape :r2)
          v
          (dm/get-prop shape :r4)])

        :r4
        (api/set-shape-corners
         [(dm/get-prop shape :r1)
          (dm/get-prop shape :r2)
          (dm/get-prop shape :r3)
          v])

        :svg-attrs
        (when (cfh/path-shape? shape)
          (api/set-shape-svg-attrs v))

        :masked-group
        (when (cfh/mask-shape? shape)
          (api/set-masked (:masked-group shape)))

        :content
        (cond
          (or (cfh/path-shape? shape)
              (cfh/bool-shape? shape))
          (api/set-shape-path-content v)

          (cfh/svg-raw-shape? shape)
          (api/set-shape-svg-raw-content (api/get-static-markup shape))

          (cfh/text-shape? shape)
          (let [pending-thumbnails (into [] (concat (api/set-shape-text-content id v)))
                pending-full (into [] (concat (api/set-shape-text-images id v)))]
            ;; FIXME: this is a hack to process the pending tasks
            ;; asynchronously we should probably modify set-wasm-attr!
            ;; to return a list of callbacks to be executed in a
            ;; second pass.
            (api/process-pending [shape] pending-thumbnails pending-full api/noop-fn)
            nil))

        :grow-type
        (api/set-shape-grow-type v)

        (:layout-item-align-self
         :layout-item-margin
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
        (do
          (api/clear-layout)
          (cond
            (ctl/grid-layout? shape)
            (api/set-grid-layout-data shape)

            (ctl/flex-layout? shape)
            (api/set-flex-layout shape)))

        ;; Property not in WASM
        nil))))

(defn process-shape!
  [shape properties]
  (let [shape-id (dm/get-prop shape :id)]
    (if (shape-in-current-page? shape-id)
      (do
        (api/use-shape shape-id)
        (->> properties
             (mapcat #(set-wasm-attr! shape %))
             (d/index-by :key :callback)
             (vals)
             (rx/from)
             (rx/mapcat (fn [callback] (callback)))
             (rx/reduce conj [])
             (rx/tap
              (fn []
                (when (cfh/text-shape? shape)
                  (api/update-text-rect! (:id shape)))))))
      (rx/empty))))

(defn process-shape-changes!
  [objects shape-changes]
  (->> (rx/from shape-changes)
       (rx/mapcat (fn [[shape-id props]] (process-shape! (get objects shape-id) props)))
       (rx/subs! #(api/request-render "set-wasm-attrs"))))

;; `conj` empty set initialization
(def conj* (fnil conj #{}))

(defn- impl-assoc
  [self k v]
  (when shape/*shape-changes*
    (vswap! shape/*shape-changes* update (:id self) conj* k))

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
  (when shape/*shape-changes*
    (vswap! shape/*shape-changes* update (:id self) conj* k))

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
