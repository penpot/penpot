;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.enums
  "Binds this build's generated enums into the shared bridge.

  `shared.js` is emitted next to this file by `render-wasm/build export` and is
  not committed. Requiring this namespace is what makes
  `app.common.render-wasm.wasm/serializers` usable."
  (:require
   ["./shared.js" :as shared]
   [app.common.render-wasm.wasm :as wasm])
  (:require-macros
   [app.common.render-wasm.enums :as enums]))

(wasm/init-serializers! (enums/serializers shared))
