;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.api.select
  "WASM cursor selection.
  Note that setters in `app.common.render-wasm.api.props` assume the caller
  selected first."
  (:require
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.uuid :as uuid]))

(defn use-shape!
  "Makes the shape with `id` current in WASM so subsequent setters apply to it.
  This is a no-op when there is no WASM context."
  [id]
  ;; Use `wasm/live?` (not `initialized?`) so context-restore reload can
  ;; select shapes while `reloading?` still blocks external app callers.
  (when (wasm/live?)
    (let [buffer (uuid/get-u32 id)]
      (h/call wasm/internal-module "_use_shape"
              (aget buffer 0)
              (aget buffer 1)
              (aget buffer 2)
              (aget buffer 3)))))
