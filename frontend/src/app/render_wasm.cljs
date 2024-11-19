;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm
  "A WASM based render API"
  (:require
   [app.common.types.shape :as shape]
   [app.render-wasm.api :as api]
   [app.render-wasm.shape :as wasm.shape]))

(def module api/module)

(defn initialize
  [enabled?]
  (set! app.common.types.shape/wasm-enabled? enabled?)
  (set! app.common.types.shape/wasm-create-shape wasm.shape/create-shape))
