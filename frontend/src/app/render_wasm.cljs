;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm
  "A WASM based render API"
  (:require
   [app.common.types.path]
   [app.common.types.shape :as shape]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.shape :as wasm.shape]))

(def module wasm.api/module)

(defn initialize
  [enabled?]
  (if enabled?
    (set! app.common.types.path/wasm:calc-bool-content wasm.api/calculate-bool)
    (set! app.common.types.path/wasm:calc-bool-content nil))
  (set! app.common.types.shape/wasm-enabled? enabled?)
  (set! app.common.types.shape/wasm-create-shape wasm.shape/create-shape))
