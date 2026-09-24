;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.serialize-shape
  "Single source of truth for the host-independent part of serializing a whole
  shape into the WASM design state.

  Both batch serializers call this so they can't drift:
   - the workspace `app.render-wasm.api/set-object` (browser), and
   - the headless exporter `app.wasm.serialize/set-shape!` (Node).

  Structural attrs (base, children, blur, shadows, masked, bool, grow) go
  through the enlarged `_set_shapes_batch` upload. Path geometry stays on the
  chunked path FFI. Host-specific parts remain in each caller AFTER this runs:
   - fills / strokes image bytes (records may already be in cold-load batch),
   - text content (fonts),
   - svg-raw markup (browser React),
   - layout (grid/flex — workspace cold-load batches flex+item via upload;
     incremental edits still use `set-shape-layout` / `set-layout-data`).

  The incremental workspace edit path (`set-wasm-attr!`) is unaffected; it keeps
  dispatching per changed key through the same underlying `props` setters."
  (:require
   [app.common.render-wasm.api.props :as props]
   [app.common.render-wasm.api.upload :as upload]))

(defn serialize-shape!
  "Applies every host-independent WASM property of `shape`."
  [shape]
  (let [type (get shape :type)]
    (upload/set-shape-upload! shape {:include-layout? false})

    (when (some? (get shape :svg-attrs))
      (props/set-shape-svg-attrs (get shape :svg-attrs)))

    (when (and (contains? #{:path :bool} type) (some? (get shape :content)))
      (props/set-shape-path-content (get shape :content)))))
