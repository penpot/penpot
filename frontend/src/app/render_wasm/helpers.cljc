;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.helpers
  #?(:cljs (:require-macros [app.render-wasm.helpers])))

(defmacro call
  "A helper for easy call wasm defined function in a module."
  [module name & params]
  (let [fn-sym (with-meta (gensym "fn-") {:tag 'function})]
    `(let [~fn-sym (cljs.core/unchecked-get ~module ~name)]
       ;; DEBUG
       ;; (println "##" ~name)
       (~fn-sym ~@params))))
