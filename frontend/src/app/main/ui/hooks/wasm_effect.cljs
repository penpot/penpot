;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.hooks.wasm-effect
  "React effects that call WASM. Kept apart from `app.main.ui.hooks`:
  the renderer loads that namespace, so it cannot require `app.main.errors`."
  (:require-macros [app.main.ui.hooks.wasm-effect])
  (:require
   [app.main.errors]))
