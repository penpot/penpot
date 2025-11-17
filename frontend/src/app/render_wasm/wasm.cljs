;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.wasm)

(defonce internal-frame-id nil)
(defonce internal-module #js {})
(defonce serializers #js {})
(defonce context-initialized? false)
