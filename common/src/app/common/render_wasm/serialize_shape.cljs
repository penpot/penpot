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
   [app.common.render-wasm.api.upload :as upload]
   [app.common.render-wasm.svg-derived :as svg-derived]))

(defn needs-shape-tail?
  "True when `shape` needs the per-shape svg-attrs/path tail after a
  structural upload: svg-attrs present, or `:path`/`:bool` with content.
  Pure predicate shared by the single and batch serializers so their
  guards cannot drift."
  [shape]
  (let [type (:type shape)]
    (or (some? (:svg-attrs shape))
        (and (contains? #{:path :bool} type)
             (some? (:content shape))))))

(defn- write-shape-tail!
  "Per-shape svg-attrs/path tail after a structural upload. The caller
  selects the shape first; these setters act on the current shape."
  [shape]
  (when (some? (:svg-attrs shape))
    (props/set-shape-svg-attrs (:svg-attrs shape)))

  (let [type (:type shape)]
    (when (and (contains? #{:path :bool} type) (some? (:content shape)))
      (props/set-shape-path-content (:content shape)))))

(defn serialize-shape!
  "Applies every host-independent WASM property of `shape`."
  [shape]
  (upload/set-shape-upload! shape {:include-layout? false})

  (when (needs-shape-tail? shape)
    (write-shape-tail! shape)))

(defn serialize-shapes-batch!
  "Structural batch upload plus per-shape svg-attrs/path tail.

  Derives via svg-derived, uploads one `_set_shapes_batch` with `opts`,
  then selects each shape needing svg-attrs/path content and applies it.
  Host text/grid/image sequencing stays in callers. Returns the prepared
  vector for the downstream host-attrs loop."
  [shapes opts select-fn]
  (let [prepared (mapv svg-derived/apply-svg-derived shapes)]
    (when (seq prepared)
      (upload/flush-shapes-batch! prepared opts)
      (doseq [shape prepared]
        (when (needs-shape-tail? shape)
          (select-fn (:id shape))
          (write-shape-tail! shape))))
    prepared))
