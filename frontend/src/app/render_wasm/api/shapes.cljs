;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api.shapes
  "Batched shape property serialization for improved WASM performance.

   This module provides a single WASM call to set all base shape properties,
   replacing multiple individual calls (use_shape, set_parent, set_shape_type,
   etc.) with one batched operation."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.serializers.color :as sr-clr]
   [app.render-wasm.wasm :as wasm]))

;; Binary layout constants matching Rust implementation:
;;
;; | Offset | Size | Field        | Type                              |
;; |--------|------|--------------|-----------------------------------|
;; | 0      | 16   | id           | UUID (4 × u32 LE)                 |
;; | 16     | 16   | parent_id    | UUID (4 × u32 LE)                 |
;; | 32     | 1    | shape_type   | u8                                |
;; | 33     | 1    | flags        | u8 (bit0: clip, bit1: hidden)     |
;; | 34     | 1    | blend_mode   | u8                                |
;; | 35     | 1    | constraint_h | u8 (0xFF = None)                  |
;; | 36     | 1    | constraint_v | u8 (0xFF = None)                  |
;; | 37     | 3    | padding      | -                                 |
;; | 40     | 4    | opacity      | f32 LE                            |
;; | 44     | 4    | rotation     | f32 LE                            |
;; | 48     | 24   | transform    | 6 × f32 LE (a,b,c,d,e,f)          |
;; | 72     | 16   | selrect      | 4 × f32 LE (x1,y1,x2,y2)          |
;; | 88     | 16   | corners      | 4 × f32 LE (r1,r2,r3,r4)          |
;; |--------|------|--------------|-----------------------------------|
;; | Total  | 104  |              |                                   |

(def ^:const BASE-PROPS-SIZE 104)
(def ^:const FLAG-CLIP-CONTENT 0x01)
(def ^:const FLAG-HIDDEN 0x02)
(def ^:const CONSTRAINT-NONE 0xFF)

(defn- write-uuid-to-heap
  "Write a UUID to the heap at the given byte offset using DataView."
  [dview offset id]
  (let [buffer (uuid/get-u32 id)]
    (.setUint32 dview offset (aget buffer 0) true)
    (.setUint32 dview (+ offset 4) (aget buffer 1) true)
    (.setUint32 dview (+ offset 8) (aget buffer 2) true)
    (.setUint32 dview (+ offset 12) (aget buffer 3) true)))

(defn- serialize-transform
  "Extract transform matrix values, defaulting to identity matrix."
  [transform]
  (if (some? transform)
    [(dm/get-prop transform :a)
     (dm/get-prop transform :b)
     (dm/get-prop transform :c)
     (dm/get-prop transform :d)
     (dm/get-prop transform :e)
     (dm/get-prop transform :f)]
    [1.0 0.0 0.0 1.0 0.0 0.0])) ; identity matrix

(defn- serialize-selrect
  "Extract selrect values."
  [selrect]
  (if (some? selrect)
    [(dm/get-prop selrect :x1)
     (dm/get-prop selrect :y1)
     (dm/get-prop selrect :x2)
     (dm/get-prop selrect :y2)]
    [0.0 0.0 0.0 0.0]))

(defn set-shape-base-props
  "Set all base shape properties (and optionally children) in a single WASM call.

   This replaces the following individual calls:
   - use-shape
   - set-parent-id
   - set-shape-type
   - set-shape-clip-content
   - set-shape-rotation
   - set-shape-transform
   - set-shape-blend-mode
   - set-shape-opacity
   - set-shape-hidden
   - set-shape-selrect
   - set-shape-corners
   - set-shape-constraints (clear + h + v)
   - set-shape-children (when include-children? is true)

   Returns nil."
  ([shape] (set-shape-base-props shape false))
  ([shape include-children?]
   (when wasm/context-initialized?
     (let [id           (dm/get-prop shape :id)
           parent-id    (get shape :parent-id)
           shape-type   (dm/get-prop shape :type)

           clip-content (if (= shape-type :frame)
                          (not (get shape :show-content))
                          false)
           hidden       (get shape :hidden false)

           flags        (cond-> 0
                          clip-content (bit-or FLAG-CLIP-CONTENT)
                          hidden       (bit-or FLAG-HIDDEN))

           blend-mode   (sr/translate-blend-mode (get shape :blend-mode))
           constraint-h (let [c (get shape :constraints-h)]
                          (if (some? c)
                            (sr/translate-constraint-h c)
                            CONSTRAINT-NONE))
           constraint-v (let [c (get shape :constraints-v)]
                          (if (some? c)
                            (sr/translate-constraint-v c)
                            CONSTRAINT-NONE))

           opacity      (d/nilv (get shape :opacity) 1.0)
           rotation     (d/nilv (get shape :rotation) 0.0)

           ;; Transform matrix
           [ta tb tc td te tf] (serialize-transform (get shape :transform))

           ;; Selrect
           selrect (get shape :selrect)
           [sx1 sy1 sx2 sy2] (serialize-selrect selrect)

           ;; Corners
           r1 (d/nilv (get shape :r1) 0.0)
           r2 (d/nilv (get shape :r2) 0.0)
           r3 (d/nilv (get shape :r3) 0.0)
           r4 (d/nilv (get shape :r4) 0.0)

           ;; Children (when batched)
           children     (when include-children?
                          (into [] (filter uuid?) (get shape :shapes)))
           child-count  (if include-children? (count children) 0)

           ;; Total buffer: 104 base + (4 child_count + 16*N child UUIDs) when batched
           total-size   (if include-children?
                          (+ BASE-PROPS-SIZE 4 (* child-count 16))
                          BASE-PROPS-SIZE)

           ;; Allocate buffer and get DataView
           offset (mem/alloc total-size)
           heap   (mem/get-heap-u8)
           dview  (js/DataView. (.-buffer heap))]

       ;; Write id (offset 0, 16 bytes)
       (write-uuid-to-heap dview offset id)

       ;; Write parent_id (offset 16, 16 bytes)
       (write-uuid-to-heap dview (+ offset 16) (d/nilv parent-id uuid/zero))

       ;; Write shape_type (offset 32, 1 byte)
       (.setUint8 dview (+ offset 32) (sr/translate-shape-type shape-type))

       ;; Write flags (offset 33, 1 byte)
       (.setUint8 dview (+ offset 33) flags)

       ;; Write blend_mode (offset 34, 1 byte)
       (.setUint8 dview (+ offset 34) blend-mode)

       ;; Write constraint_h (offset 35, 1 byte)
       (.setUint8 dview (+ offset 35) constraint-h)

       ;; Write constraint_v (offset 36, 1 byte)
       (.setUint8 dview (+ offset 36) constraint-v)

       ;; Padding at offset 37-39 (already zero from alloc)

       ;; Write opacity (offset 40, f32)
       (.setFloat32 dview (+ offset 40) opacity true)

       ;; Write rotation (offset 44, f32)
       (.setFloat32 dview (+ offset 44) rotation true)

       ;; Write transform matrix (offset 48, 6 × f32)
       (.setFloat32 dview (+ offset 48) ta true)
       (.setFloat32 dview (+ offset 52) tb true)
       (.setFloat32 dview (+ offset 56) tc true)
       (.setFloat32 dview (+ offset 60) td true)
       (.setFloat32 dview (+ offset 64) te true)
       (.setFloat32 dview (+ offset 68) tf true)

       ;; Write selrect (offset 72, 4 × f32)
       (.setFloat32 dview (+ offset 72) sx1 true)
       (.setFloat32 dview (+ offset 76) sy1 true)
       (.setFloat32 dview (+ offset 80) sx2 true)
       (.setFloat32 dview (+ offset 84) sy2 true)

       ;; Write corners (offset 88, 4 × f32)
       (.setFloat32 dview (+ offset 88) r1 true)
       (.setFloat32 dview (+ offset 92) r2 true)
       (.setFloat32 dview (+ offset 96) r3 true)
       (.setFloat32 dview (+ offset 100) r4 true)

       ;; Write children (offset 104+) when batched
       (when include-children?
         (.setUint32 dview (+ offset 104) child-count true)
         (loop [i 0
                cs (seq children)]
           (when cs
             (write-uuid-to-heap dview (+ offset 108 (* i 16)) (first cs))
             (recur (inc i) (next cs)))))

       (h/call wasm/internal-module "_set_shape_base_props")

       nil))))

;; Binary layout for batched blur + shadows:
;;
;; Header (12 bytes):
;; | Offset | Size | Field         | Type       |
;; |--------|------|---------------|------------|
;; | 0      | 1    | blur_present  | u8         |
;; | 1      | 1    | blur_type     | u8         |
;; | 2      | 1    | blur_hidden   | u8         |
;; | 3      | 1    | padding       | -          |
;; | 4      | 4    | blur_value    | f32 LE     |
;; | 8      | 4    | shadow_count  | u32 LE     |
;;
;; Per shadow (24 bytes each):
;; | Offset | Size | Field    | Type       |
;; |--------|------|----------|------------|
;; | 0      | 4    | color    | u32 LE     |
;; | 4      | 4    | blur     | f32 LE     |
;; | 8      | 4    | spread   | f32 LE     |
;; | 12     | 4    | offset_x | f32 LE     |
;; | 16     | 4    | offset_y | f32 LE     |
;; | 20     | 1    | style    | u8         |
;; | 21     | 1    | hidden   | u8         |
;; | 22     | 2    | padding  | -          |

(def ^:const EFFECTS-HEADER-SIZE 12)
(def ^:const SHADOW-ENTRY-SIZE 24)

(defn set-shape-effects
  "Set blur and shadows in a single WASM call.

   Replaces:
   - set-shape-blur / clear-shape-blur
   - clear-shape-shadows + N × add-shape-shadow

   Returns nil."
  [blur shadows]
  (when wasm/context-initialized?
    (let [shadow-count (count shadows)
          total-size   (+ EFFECTS-HEADER-SIZE (* shadow-count SHADOW-ENTRY-SIZE))
          offset       (mem/alloc total-size)
          heap         (mem/get-heap-u8)
          dview        (js/DataView. (.-buffer heap))]

      ;; Write blur header
      (if (some? blur)
        (let [type   (-> blur :type sr/translate-blur-type)
              hidden (if (:hidden blur) 1 0)
              value  (:value blur)]
          (.setUint8 dview offset 1)          ;; blur_present
          (.setUint8 dview (+ offset 1) type) ;; blur_type
          (.setUint8 dview (+ offset 2) hidden) ;; blur_hidden
          (.setFloat32 dview (+ offset 4) value true)) ;; blur_value
        (do
          (.setUint8 dview offset 0)          ;; blur_present = 0
          (.setUint8 dview (+ offset 1) 0)
          (.setUint8 dview (+ offset 2) 0)
          (.setFloat32 dview (+ offset 4) 0.0 true)))

      ;; Write shadow count
      (.setUint32 dview (+ offset 8) shadow-count true)

      ;; Write shadow entries
      (loop [i 0
             shadows-seq (seq shadows)]
        (when shadows-seq
          (let [shadow       (first shadows-seq)
                entry-offset (+ offset EFFECTS-HEADER-SIZE (* i SHADOW-ENTRY-SIZE))
                color        (get shadow :color)
                rgba         (sr-clr/hex->u32argb (get color :color)
                                                  (get color :opacity))]
            (.setUint32 dview entry-offset rgba true)
            (.setFloat32 dview (+ entry-offset 4) (get shadow :blur) true)
            (.setFloat32 dview (+ entry-offset 8) (get shadow :spread) true)
            (.setFloat32 dview (+ entry-offset 12) (get shadow :offset-x) true)
            (.setFloat32 dview (+ entry-offset 16) (get shadow :offset-y) true)
            (.setUint8 dview (+ entry-offset 20) (sr/translate-shadow-style (get shadow :style)))
            (.setUint8 dview (+ entry-offset 21) (if (get shadow :hidden) 1 0))
            (recur (inc i) (next shadows-seq)))))

      (h/call wasm/internal-module "_set_shape_effects")
      nil)))
