;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.renderer.wasm
  "Headless renderer backend: renders exports with the render-wasm Skia
  pipeline, with no browser and no WebGL.

  Drop-in alternative to `app.renderer.bitmap`/`pdf` (the Playwright
  backends), selected from `app.renderer` when an export is flagged headless.
  Handles png/jpeg/webp (Skia encodes all three natively) and pdf; `:svg` is
  routed to the browser backend by `app.renderer` — it needs vector markup,
  not a raster.

  Only the entry point lives here. The render itself is synchronous wasm work,
  so it runs in a worker thread rather than on the event loop: `app.wasm.pool`
  schedules it and `app.wasm.render` is the pipeline."
  (:require
   [app.wasm.pool :as pool]))

(defn render
  [params on-object]
  (pool/render params on-object))
