;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.protocol)

(defmacro resolve-or-reject
  "Evaluates `body` and returns a Promise. A synchronous throw rejects the
  returned promise."
  [& body]
  `(try
     (js/Promise.resolve (do ~@body))
     (catch :default cause#
       (js/Promise.reject cause#))))

(defmacro resolve-or-reject-with
  "Evaluates `body` and returns a Promise. A synchronous throw rejects with the
  result of `on-error`, evaluated with `cause` bound to the thrown value."
  [cause on-error & body]
  `(try
     (js/Promise.resolve (do ~@body))
     (catch :default cause#
       (let [~cause cause#]
         (js/Promise.reject ~on-error)))))
