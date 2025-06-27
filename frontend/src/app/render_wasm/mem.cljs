;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.mem
  (:require
   [app.render-wasm.helpers :as h]
   [app.render-wasm.wasm :as wasm]))

(defn ptr8->ptr32
  "Returns a 32-bit (4-byte aligned) pointer of an 8-bit pointer"
  [value]
  ;; Divides the value by 4
  (bit-shift-right value 2))

(defn ptr32->ptr8
  "Returns a 8-bit pointer of a 32-bit (4-byte aligned) pointer"
  [value]
  ;; Multiplies by 4
  (bit-shift-left value 2))

(defn get-list-size
  "Returns the size of a list in bytes"
  [list list-item-size]
  (* list-item-size (count list)))

(defn alloc-bytes
  "Allocates an arbitrary amount of bytes"
  [size]
  (when (= size 0)
    (js/console.trace "Tried to allocate 0 bytes"))
  (h/call wasm/internal-module "_alloc_bytes" size))

(defn alloc-bytes-32
  "Allocates a 4-byte aligned amount of bytes"
  [size]
  (when (= size 0)
    (js/console.trace "Tried to allocate 0 bytes"))
  (ptr8->ptr32 (h/call wasm/internal-module "_alloc_bytes" size)))

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
