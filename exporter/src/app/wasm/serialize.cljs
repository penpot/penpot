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

  COVERAGE (current slice): base props (incl. corners/opacity/transform/
  clip/constraints), children, fills, strokes, layer + background blur. Solid
  + gradient fills/strokes render fully. NOT YET: text (needs the font DB),
  path/bool geometry, shadows, svg-raw, image fills/strokes (image bytes are
  not uploaded yet — those records serialize but render empty)."
  (:require
   [app.common.types.fills.impl :as types.fills.impl]
   [app.render-wasm.api.props :as props]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.serialize-shape :as serialize-shape]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.wasm :as wasm]
   [app.wasm.text :as text]))

(defn- set-shape-strokes!
  "Mirrors the browser's stroke serialization (solid + gradient). Image
  strokes are skipped for now."
  [strokes]
  (h/call wasm/internal-module "_clear_shape_strokes")
  (doseq [stroke strokes
          :when (not (:hidden stroke))]
    (let [opacity   (or (:stroke-opacity stroke) 1.0)
          color     (:stroke-color stroke)
          gradient  (:stroke-color-gradient stroke)
          width     (:stroke-width stroke)
          align     (:stroke-alignment stroke)
          style     (-> stroke :stroke-style sr/translate-stroke-style)
          cap-start (-> stroke :stroke-cap-start sr/translate-stroke-cap)
          cap-end   (-> stroke :stroke-cap-end sr/translate-stroke-cap)
          dash      (or (:stroke-dash stroke) -1)
          gap       (or (:stroke-gap stroke) -1)
          offset    (mem/alloc types.fills.impl/FILL-U8-SIZE)
          heap      (mem/get-heap-u8)
          dview     (js/DataView. (.-buffer heap))]
      (case align
        :inner (h/call wasm/internal-module "_add_shape_inner_stroke" width style cap-start cap-end dash gap)
        :outer (h/call wasm/internal-module "_add_shape_outer_stroke" width style cap-start cap-end dash gap)
        (h/call wasm/internal-module "_add_shape_center_stroke" width style cap-start cap-end dash gap))
      (cond
        (some? gradient)
        (do (types.fills.impl/write-gradient-fill offset dview opacity gradient)
            (h/call wasm/internal-module "_add_shape_stroke_fill"))

        (some? color)
        (do (types.fills.impl/write-solid-fill offset dview opacity color)
            (h/call wasm/internal-module "_add_shape_stroke_fill"))))))

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
      (set-shape-strokes! (get shape :strokes)))
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
