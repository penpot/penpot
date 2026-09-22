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
      (frame {:x 0 :y 0 :width 100 :height 100}
        (rect))
      (group :tiles {}
        (rect [:tile 3] {}))
      (bool :diff {:bool-type :difference}
        (rect {:x 0 :y 0 :width 100 :height 100})
        (rect {:x 50 :y 50 :width 100 :height 100})))

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

(defmacro circle
  "Adds one ellipse to the current fixture scope and returns its uuid.

  Forms: `(circle)`, `(circle attrs)`, `(circle label)`, `(circle label attrs)`.
  A one-argument call is resolved at runtime: a map is `attrs`, anything
  else is a label. `label` must be a keyword or vector, stored in the
  instance `:refs`; duplicates and other label types are rejected. `attrs`
  override the generated defaults attribute by attribute."
  ([]
   `(benches.render-wasm.scenes.builder/circle!
     benches.render-wasm.scenes.builder/*state*
     benches.render-wasm.scenes.builder/*defaults*
     nil
     {}))

  ([label-or-attrs]
   `(benches.render-wasm.scenes.builder/circle!
     benches.render-wasm.scenes.builder/*state*
     benches.render-wasm.scenes.builder/*defaults*
     ~label-or-attrs
     {}))

  ([label attrs]
   `(benches.render-wasm.scenes.builder/circle!
     benches.render-wasm.scenes.builder/*state*
     benches.render-wasm.scenes.builder/*defaults*
     ~label
     ~attrs)))

(defmacro frame
  "Adds a frame to the current scope, runs `body` with the frame as parent,
  and returns the frame uuid.

  Forms: `(frame attrs & body)` and `(frame label attrs & body)`. `label` is
  a literal keyword or vector stored in the instance `:refs`. Frame attrs
  must carry `:x :y :width :height`; children keep page-absolute
  coordinates. Empty frames are allowed."
  [label-or-attrs & body]
  (if (or (keyword? label-or-attrs) (vector? label-or-attrs))
    `(benches.render-wasm.scenes.builder/with-container
       :frame
       ~(first body)
       ~label-or-attrs
       (fn [] ~@(rest body)))
    `(benches.render-wasm.scenes.builder/with-container
       :frame
       ~label-or-attrs
       nil
       (fn [] ~@body))))

(defmacro group
  "Adds a group to the current scope, runs `body` with the group as parent,
  and returns the group uuid.

  Forms: `(group attrs & body)` and `(group label attrs & body)`. `label` is
  a literal keyword or vector stored in the instance `:refs`. Groups derive
  their geometry from children, so geometry attrs are rejected. With
  `:masked-group true` the first child is the mask. A group needs at least
  one child."
  [label-or-attrs & body]
  (if (or (keyword? label-or-attrs) (vector? label-or-attrs))
    `(benches.render-wasm.scenes.builder/with-container
       :group
       ~(first body)
       ~label-or-attrs
       (fn [] ~@(rest body)))
    `(benches.render-wasm.scenes.builder/with-container
       :group
       ~label-or-attrs
       nil
       (fn [] ~@body))))

(defmacro bool
  "Adds a boolean shape to the current scope, runs `body` with the bool as
  parent, and returns the bool uuid.

  Forms: `(bool attrs & body)` and `(bool label attrs & body)`. `label` is a
  literal keyword or vector stored in the instance `:refs`. `attrs` must
  carry `:bool-type` (`:union`, `:difference`, `:intersection` or
  `:exclude`). Content and geometry derive from the children after they
  finalize, so geometry, content and transform attrs are rejected. Fills,
  strokes, shadows and blurs are inherited from the head child (first for
  `:difference`, last otherwise) unless attrs supply them. A bool needs at
  least one child and cannot contain frames."
  [label-or-attrs & body]
  (if (or (keyword? label-or-attrs) (vector? label-or-attrs))
    `(benches.render-wasm.scenes.builder/with-container
       :bool
       ~(first body)
       ~label-or-attrs
       (fn [] ~@(rest body)))
    `(benches.render-wasm.scenes.builder/with-container
       :bool
       ~label-or-attrs
       nil
       (fn [] ~@body))))
