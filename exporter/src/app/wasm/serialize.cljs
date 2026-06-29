;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.serialize
  "Browser-free shape serialization for the headless exporter.

  This is the headless counterpart of the browser orchestrator
  `app.render-wasm.api/set-object`. It cannot reuse that fn directly (its
  namespace pulls React/DOM/store), so it owns the *call sequencing* here —
  but every byte layout is reused from the shared, identical sources:

   - `app.render-wasm.api.shapes/set-shape-base-props` (the scalar struct),
   - `app.common.types.fills` / `…fills.impl` (fills & stroke fills),
   - `app.render-wasm.{mem,serializers,helpers}` leaves.

  So the bytes sent to WASM are the same as the editor's; only the (simple)
  orchestration lives here.

  COVERAGE: base props (incl. corners/opacity/transform/clip/constraints),
  children, fills (solid/gradient/image), strokes (solid/gradient/image),
  layer + background blur, shadows, masks, path/bool geometry, text. Image
  BYTES and fonts are provisioned separately by `app.renderer.wasm`.
  NOT YET: svg-raw (needs faithful static markup — see step 3d notes)."
  (:require
   [app.render-wasm.api.props :as props]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.serialize-shape :as serialize-shape]
   [app.render-wasm.wasm :as wasm]
   [app.wasm.text :as text]))

(defn set-shape!
  "Serializes a single shape into the WASM design state. The host-independent
  properties (base props, children, blur, shadows, svg-attrs, mask, bool-type,
  path geometry, grow-type) go through the shared `serialize-shape!` — the same
  code the workspace's `set-object` uses, so the two can't drift. Only the
  host-specific parts are handled here: fills/strokes (image bytes are provisioned
  separately) and text content (fonts provisioned separately)."
  [shape]
  (let [type (get shape :type)]
    (serialize-shape/serialize-shape! shape)
    (props/write-shape-fills! (get shape :fills))
    (when-not (= type :group)
      (props/write-shape-strokes! (get shape :strokes)))
    (when (= type :text)
      (text/set-shape-text! (get shape :content)))))

(defn serialize-scene!
  "Loads every shape of an `objects` map into the WASM design state. Resets the
  shapes pool first so repeated exports don't accumulate into the shared
  state. Order is irrelevant: shapes reference each other by id and the tree
  is resolved at render time."
  [objects]
  (h/call wasm/internal-module "_init_shapes_pool" (count objects))
  (run! set-shape! (vals objects)))
