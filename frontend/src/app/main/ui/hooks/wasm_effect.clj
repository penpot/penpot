;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.hooks.wasm-effect
  (:require
   [rumext.v2 :as mf]))

(defmacro with-wasm-effect
  "Like `mf/with-effect`, but a recoverable WASM error shows a toast
  instead of reaching the React error boundary."
  [deps & body]
  `(mf/with-effect ~deps
     (app.main.errors/wrap-wasm-effect (fn [] ~@body))))
