;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.mem
  (:require
   [app.common.buffer :as buf]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.wasm :as wasm]))

(defn ->offset-32
  "Convert a 8-bit (1 byte) offset to a 32-bit (4 bytes) offset"
  [value]
  ;; Divides the value by 4
  (bit-shift-right value 2))

(defn get-alloc-size
  "Calculate allocation size for a sequential collection of identical
  objects of the specified size."
  [coll item-size]
  (assert (counted? coll) "`coll` should be constant time countable")
  (* item-size (count coll)))

(defn alloc
  "Allocates an arbitrary amount of bytes (aligned to 4 bytes).
  Returns an offset of 8 bits (1 byte) size."
  [size]
  (when (= size 0)
    (js/console.trace "Tried to allocate 0 bytes"))
  (h/call wasm/internal-module "_alloc_bytes" size))

(defn alloc->offset-32
  "Allocates an arbitrary amount of bytes (aligned to 4 bytes).
  Returns an offset of 32 bits (4 bytes) size."
  [size]
  (-> (alloc size) (->offset-32)))

(defn get-heap-u8
  "Returns a Uint8Array view of the heap"
  []
  (unchecked-get ^js wasm/internal-module "HEAPU8"))

(defn get-heap-u32
  "Returns a Uint32Array view of the heap"
  []
  (unchecked-get ^js wasm/internal-module "HEAPU32"))

(defn get-heap-i32
  "Returns a Uint32Array view of the heap"
  []
  (unchecked-get ^js wasm/internal-module "HEAP32"))

(defn get-heap-f32
  "Returns a Float32Array view of the heap"
  []
  (unchecked-get ^js wasm/internal-module "HEAPF32"))

(defn free
  []
  (h/call wasm/internal-module "_free_bytes"))

(defn slice
  "Returns a copy of a portion of a typed array into a new typed array
  object selected from start to end."
  [heap offset size]
  (.slice ^js heap offset (+ offset size)))

(defn get-data-view
  "Returns a heap wrapped in a DataView for surgical write operations"
  []
  (buf/wrap (get-heap-u8)))

(defn write-u8
  "Write unsigned int8. Expects a DataView instance"
  [offset target value]
  (buf/write-u8 target offset value)
  (+ offset 1))

(defn write-f32
  "Write float32. Expects a DataView instance"
  [offset target value]
  (buf/write-f32 target offset value)
  (+ offset 4))

(defn write-i32
  "Write int32. Expects a DataView instance"
  [offset target value]
  (buf/write-i32 target offset value)
  (+ offset 4))

(defn write-u32
  "Write int32. Expects a DataView instance"
  [offset target value]
  (buf/write-i32 target offset value)
  (+ offset 4))

(defn write-bool
  "Write int32. Expects a DataView instance"
  [offset target value]
  (buf/write-bool target offset value)
  (+ offset 1))

(defn write-uuid
  "Write uuid. Expects a DataView instance"
  [offset target value]
  (buf/write-uuid target offset value)
  (+ offset 16))

(defn write-buffer
  [offset target value]
  (assert (instance? js/Uint8Array target) "target should be u8 addressable heap")

  (let [value (cond
                (instance? js/ArrayBuffer value)
                (new js/Uint8Array. value)

                (instance? js/Uint8Array value)
                value

                :else
                (throw (js/Error. "unexpected type")))]
    (.set ^js target value offset)
    (+ offset (.-byteLength value))))

(defn assert-written
  [final-offset prev-offset expected]
  (assert (= expected (- final-offset prev-offset))
          (str "expected to be written " expected " but finally writted " (- final-offset prev-offset)))
  final-offset)

(defn size
  "Get buffer size"
  [o]
  (.-byteLength ^js o))

