;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.serialize-shape
  "Single source of truth for the host-independent part of serializing a whole
  shape into the WASM design state.

  Both batch serializers call this so they can't drift:
   - the workspace `app.render-wasm.api/set-object` (browser), and
   - the headless exporter `app.wasm.serialize/set-shape!` (Node).

  It applies only the properties that need no host-specific resources or driver:
  base props, children, blur, background blur, shadows, svg attrs, group mask,
  bool type, path/bool geometry and text grow type. The parts that DO differ by
  host are handled by each caller AFTER this runs:
   - fills / strokes (image bytes are fetched + uploaded differently),
   - text content (fonts),
   - svg-raw markup (browser renders it via React),
   - layout (grid/flex — workspace only).

  The incremental workspace edit path (`set-wasm-attr!`) is unaffected; it keeps
  dispatching per changed key through the same underlying `props` setters."
  (:require
   [app.render-wasm.api.props :as props]
   [app.render-wasm.api.shapes :as shapes]))

(defn serialize-shape!
  "Applies every host-independent WASM property of `shape`. `set-shape-base-props`
  runs first because it selects the current shape (`use_shape`) the rest mutate."
  [shape]
  (let [type (get shape :type)]
    (shapes/set-shape-base-props shape)
    (props/set-shape-children (get shape :shapes))
    (props/set-shape-blur (get shape :blur))
    (props/set-shape-background-blur (get shape :background-blur))
    (props/set-shape-shadows (get shape :shadow))

    (when (some? (get shape :svg-attrs))
      (props/set-shape-svg-attrs (get shape :svg-attrs)))

    (when (= type :group)
      (props/set-masked (boolean (get shape :masked-group))))

    (when (= type :bool)
      (props/set-shape-bool-type (get shape :bool-type)))

    (when (and (contains? #{:path :bool} type) (some? (get shape :content)))
      (props/set-shape-path-content (get shape :content)))

    (when (= type :text)
      (props/set-shape-grow-type (get shape :grow-type)))))
