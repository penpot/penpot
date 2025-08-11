;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.mem
  (:require
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

(defn view
  "Returns a new typed array on the same ArrayBuffer store and with the
  same element types as for this typed array."
  [heap offset size]
  (.subarray ^js heap offset (+ offset size)))
