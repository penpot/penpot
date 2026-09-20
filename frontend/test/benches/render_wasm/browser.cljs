;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.browser
  "Browser entry for the CLJS renderer benchmark suite.

  Requiring `app.render-wasm.api.enums` binds the enum table from the
  prepared renderer build's `shared.js` at namespace load time. A missing
  `shared.js` fails the compile; a stale or mismatched one throws at load.

  The exported `bridge` is the only handle the Node runner uses inside the
  page. Keep the boundary plain data: never pass a compiled CLJS closure
  through `page.evaluate`."
  (:require
   [app.render-wasm.api.enums]))

(defn ping
  "Bridge placeholder that checks the Node/browser boundary.

  TODO(mem:render-wasm/performance/cljs-rewrite/05-browser-load-pilot):
  replace with the renderer load and measurement protocol."
  []
  #js {"status" "ok"})

(def bridge
  "Browser-side bridge exported from the compiled module.

  TODO(mem:render-wasm/performance/cljs-rewrite/06-render-and-interaction-protocol):
  add `runAttempt`, `dispose` and the render completion protocol."
  #js {"ping" ping})
