;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.helpers
  #?(:cljs (:require-macros [app.common.render-wasm.helpers]))
  (:require [app.common.data :as d]))

(def error-code
  "WASM error code constants (must match render-wasm/src/error.rs and mem.rs)."
  {0x01 :non-blocking
   0x02 :panic})

(defmacro call
  "Calls the WASM function `name`. On failure, throws a `:wasm-error` whose
   `:code` comes from `error-code`, or `:wasm-critical` when WASM set none."
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
