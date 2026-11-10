;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.helpers
  #?(:cljs (:require-macros [app.common.render-wasm.helpers])))

(def error-code
  "WASM error code constants (must match render-wasm/src/error.rs and mem.rs)."
  {0x01 :non-blocking
   0x02 :panic})

#?(:cljs
   (defn on-call-error
     "Restores the stack pointer after a failed WASM call and throws the
     classified error."
     [module fn-name stack cause]
     (when (some? stack)
       ((unchecked-get module "stackRestore") stack))
     (let [read-code-fn (unchecked-get module "_read_error_code")
           code-num     (when read-code-fn (read-code-fn))
           code         (get error-code code-num :wasm-critical)
           hint         (str "WASM Error (" (name code) ")")]
       (throw (ex-info hint {:type :wasm-error :code code :hint hint :fn fn-name} cause)))))

(defmacro call
  "A helper for calling a wasm  function.
   Catches any exception thrown by the WASM function, reads the error code from
   WASM when available, and routes it based on the error type:
   - :wasm-non-blocking: call app.main.errors/on-error (eventually, shows a toast and logs the error)
   - :wasm-critical or unknown: throws an exception to be handled by the global error handler (eventually, shows the internal error page)
   Restores the stack pointer on failure, since a trap leaves its shadow-stack frames behind."
  [module name & params]
  (let [fn-sym     (with-meta (gensym "fn-") {:tag 'function})
        module-sym (gensym "module")
        stack-sym  (gensym "stack")]
    `(let [~module-sym ~module
           ~fn-sym     (cljs.core/unchecked-get ~module-sym ~name)
           save-fn#    (cljs.core/unchecked-get ~module-sym "stackSave")
           ~stack-sym  (when save-fn# (save-fn#))]
       (try
         (~fn-sym ~@params)
         (catch :default cause#
           (on-call-error ~module-sym ~name ~stack-sym cause#))))))
