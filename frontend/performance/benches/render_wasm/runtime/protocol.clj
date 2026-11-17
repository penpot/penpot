;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.protocol
  "Forms for evaluating renderer operations as Promises.
  A Promise represents a result that can complete with a value or reject with
  a cause. These forms include synchronous errors in that result.")

(defmacro resolve-or-reject
  "Evaluates `body` and returns a Promise of its result.
  A synchronous throw rejects the Promise."
  [& body]
  `(try
     (js/Promise.resolve (do ~@body))
     (catch :default cause#
       (js/Promise.reject cause#))))

(defmacro resolve-or-reject-with
  "Evaluates `body` and returns a Promise of its result.
  A synchronous throw rejects with `on-error`, evaluated with `cause` bound
  to the thrown value."
  [cause on-error & body]
  `(try
     (js/Promise.resolve (do ~@body))
     (catch :default cause#
       (let [~cause cause#]
         (js/Promise.reject ~on-error)))))
