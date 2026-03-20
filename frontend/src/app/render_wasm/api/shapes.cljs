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
  "Set all base shape properties in a single WASM call.

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

   Returns nil."
  [shape]
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

          ;; Allocate buffer and get DataView
          offset (mem/alloc BASE-PROPS-SIZE)
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

      (h/call wasm/internal-module "_set_shape_base_props")

      nil)))
