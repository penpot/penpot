;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.core
  "Registration macros for renderer benchmark scenes and cases.

  The macros expand to calls to `register-scene!`/`register-case!` in
  `core.cljs`. Registration is done there so it can be inspected and tested.")

(defmacro defscene
  "Registers a scene and returns its id.

   - `id` (keyword)
   - `opts` (`schema:scene`):  maps `:version`, `:description` and
     `:params-schema`.
   - `build` resolves to a one-argument function of the scene parameters."
  [id opts build]
  `(benches.render-wasm.scenes.core/register-scene!
    (assoc ~opts :id ~id :ns ~(str *ns*) :build ~build)))

(defmacro defcase
  "Registers a case for `scene` and returns its id.

  The optional body is the function run by the browser for the case. It sees
  the injected runtime as `rtx`. Case collection strips the body before
  handing descriptors to the runner."
  [id scene opts & body]
  (if (seq body)
    `(benches.render-wasm.scenes.core/register-case!
      (assoc ~opts
             :id ~id
             :scene ~scene
             :ns ~(str *ns*)
             :run! (fn [~'rtx] ~@body)))
    `(benches.render-wasm.scenes.core/register-case!
      (assoc ~opts :id ~id :scene ~scene :ns ~(str *ns*)))))
