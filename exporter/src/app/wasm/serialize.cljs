;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.serialize
  "Browser-free shape serialization for the headless exporter: the counterpart
  of `app.render-wasm.api/set-object`, which cannot be reused directly because
  its namespace pulls React/DOM/store. Only the call sequencing lives here —
  every byte layout comes from the shared serializers, so the bytes sent to
  WASM are the editor's.

  Covers everything except svg-raw. Image bytes and fonts are provisioned
  separately by `app.renderer.wasm`."
  (:require
   [app.common.render-wasm.api.props :as props]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.serialize-shape :as serialize-shape]
   [app.common.render-wasm.wasm :as wasm]
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
