;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.errors
  "Macro half of `errors.cljs`.")

(defmacro attempt
  "Evaluates `body` and returns its value, or the failure it throws.

  A thrown value that is not an Error becomes an Error (`as-failure`). A fatal
  failure (`fatal?`) is thrown again; with a leading `:catch-fatal` it is
  returned like any other failure.

  Inside an `^:async` function `body` may use `await`. `failure?` tells a
  returned failure from a value.

  Examples:

    (attempt (parse text))
    (attempt (await (open-page owner)))
    (attempt :catch-fatal (await (run-session state)))"
  [& forms]
  (let [catch-fatal? (= :catch-fatal (first forms))
        body         (if catch-fatal? (rest forms) forms)
        thrown       (gensym "thrown")
        failure      (gensym "failure")]
    `(try
       ~@body
       (catch :default ~thrown
         (let [~failure (benches.render-wasm.errors/as-failure ~thrown)]
           ~(if catch-fatal?
              failure
              `(if (benches.render-wasm.errors/fatal? ~failure)
                 (throw ~failure)
                 ~failure)))))))
