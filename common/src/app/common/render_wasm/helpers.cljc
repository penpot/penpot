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
  "Calls the WASM function `name`. On failure, throws a `:wasm-error` whose
   `:code` comes from `error-code`, or `:wasm-critical` when WASM set none.
   Restores the stack pointer on failure, since a trap leaves its shadow-stack
   frames behind."
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
