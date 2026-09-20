;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.builder
  "Construction macros for renderer benchmark fixtures.

  Runtime helpers and scope state live in the sibling `builder.cljs`.

  Usage:

    (fixture {:seed 42
              :defaults {:rect {:x (gen-int 0 1920)}}}
      (rect)                  ; anonymous, generated attrs
      (rect {:x 10})          ; literal x over the generated rest
      (rect :hero {})         ; label stored in instance :refs
      (rect [:tile 3] {}))    ; vector label stored in instance :refs

  `params` is a map with `:seed` (required), optional `:root` attributes for
  the canonical root frame, and optional `:defaults` with one attribute
  generator map per shape type.

  Consumers require this namespace with `:include-macros true` (or
  `:require-macros`) to use the macros; the runtime helpers live under the
  same alias.

  The scope is carried by the dynamic `*state*`, so ordinary `let`, `doseq`
  and functions defined outside the scope work inside the body. Scopes
  cannot nest and a body exception unwinds without leaking state.")

(defmacro fixture
  "Runs `body` in a fixture scope and returns the validated snapshot."
  [params & body]
  `(let [params# ~params
         state#  (benches.render-wasm.scenes.builder/start params#)]
     (binding [benches.render-wasm.scenes.builder/*state* state#
               benches.render-wasm.scenes.builder/*defaults* (:defaults params#)]
       ~@body
       (benches.render-wasm.scenes.builder/finish! state#))))

(defmacro rect
  "Adds one rectangle to the current fixture scope and returns its uuid.

  Forms: `(rect)`, `(rect attrs)`, `(rect label)`, `(rect label attrs)`.
  A one-argument call is resolved at runtime: a map is `attrs`, anything
  else is a label. `label` must be a keyword or vector, stored in the
  instance `:refs`; duplicates and other label types are rejected. `attrs`
  override the generated defaults attribute by attribute."
  ([]
   `(benches.render-wasm.scenes.builder/rect!
     benches.render-wasm.scenes.builder/*state*
     benches.render-wasm.scenes.builder/*defaults*
     nil
     {}))

  ([label-or-attrs]
   `(benches.render-wasm.scenes.builder/rect!
     benches.render-wasm.scenes.builder/*state*
     benches.render-wasm.scenes.builder/*defaults*
     ~label-or-attrs
     {}))

  ([label attrs]
   `(benches.render-wasm.scenes.builder/rect!
     benches.render-wasm.scenes.builder/*state*
     benches.render-wasm.scenes.builder/*defaults*
     ~label
     ~attrs)))
