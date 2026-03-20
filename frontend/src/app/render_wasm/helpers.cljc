;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.helpers
  #?(:cljs (:require-macros [app.render-wasm.helpers]))
  (:require [app.common.data :as d]))

(def error-code
  "WASM error code constants (must match render-wasm/src/error.rs and mem.rs)."
  {0x01 :non-blocking
   0x02 :panic})

(defmacro call
  "A helper for calling a wasm  function.
   Catches any exception thrown by the WASM function, reads the error code from
   WASM when available, and routes it based on the error type:
   - :wasm-non-blocking: call app.main.errors/on-error (eventually, shows a toast and logs the error)
   - :wasm-critical or unknown: throws an exception to be handled by the global error handler (eventually, shows the internal error page)"
  [module name & params]
  (let [fn-sym    (with-meta (gensym "fn-") {:tag 'function})
        cause-sym (gensym "cause")]
    `(let [~fn-sym (cljs.core/unchecked-get ~module ~name)]
       (try
         (~fn-sym ~@params)
         (catch :default ~cause-sym
           (let [read-code-fn# (cljs.core/unchecked-get ~module "_read_error_code")
                 code-num#     (when read-code-fn# (read-code-fn#))
                 code#         (get error-code code-num# :wasm-critical)
                 hint#         (str "WASM Error (" (d/name code#)   ")")
                 context#      {:type :wasm-error
                                :code code#
                                :hint hint#
                                :fn ~name}
                 cause#        (ex-info hint# context# ~cause-sym)]
             (throw cause#)))))))
