;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.api.upload
  "WASM heap flush for structural cold-load batches.

  Pure encode lives in `app.common.render-wasm.api.upload-encode`."
  (:require
   [app.common.buffer :as buf]
   [app.common.render-wasm.api.upload-encode :as encode]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.wasm :as wasm]))

(def write-shape-payload! encode/write-shape-payload!)
(def encode-shape-record encode/encode-shape-record)
(def encode-shapes-batch encode/encode-shapes-batch)
(def shapes-in-tree-order encode/shapes-in-tree-order)
(def encode-page-objects encode/encode-page-objects)
(def PROTOCOL-VERSION encode/PROTOCOL-VERSION)

(defn flush-shapes-batch-bytes!
  "Upload a pre-encoded `_set_shapes_batch` buffer (Uint8Array)."
  [batch-bytes]
  (when (and (wasm/live?) batch-bytes)
    (let [total  (.-byteLength ^js batch-bytes)
          offset (mem/alloc total)
          heap   (mem/get-heap-u8)]
      (.set heap batch-bytes offset)
      (h/call wasm/internal-module "_set_shapes_batch")
      nil)))

(defn flush-shapes-batch!
  "Upload `shapes` as one `_set_shapes_batch` call.
   `opts` passed to each record writer (`:include-layout?`,
   `:include-fills-strokes?`)."
  [shapes opts]
  (when (and (wasm/live?) (seq shapes))
    (flush-shapes-batch-bytes! (encode/encode-shapes-batch shapes opts))))

(defn set-shape-upload!
  "Single-shape structural upload (enlarged blob, one FFI)."
  ([shape]
   (set-shape-upload! shape {:include-layout? false}))
  ([shape opts]
   (flush-shapes-batch! [shape] opts)))
