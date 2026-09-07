;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.wasm)

(defonce internal-frame-id nil)
(defonce internal-frame-type 0)
(defonce internal-module #js {})

;; Reference to the HTML canvas element.
(defonce canvas nil)
;; Snapshot of the current canvas as an `ImageBitmap`, suitable for painting
;; into an overlay canvas. Created via `createImageBitmap` so capturing never
;; encodes pixels on the main thread.
(defonce canvas-snapshot nil)

;; Reference to the Emscripten GL context wrapper.
(defonce gl-context-handle nil)

;; Reference to the actual WebGL Context returned
;; by the `.getContext` method of the canvas.
(defonce gl-context nil)

(defonce context-initialized? false)
(defonce context-lost? (atom false))

;; True while `reload-renderer!` is reconstructing the GL/WASM pipeline after a
;; WebGL context restore (or an explicit reload). External app callers must not
;; touch WASM until this clears (`ready?`); reload-internal ops use `live?`.
(defonce reloading? (atom false))

;; When we're rendering in a sync way we want to stop the asynchrous `request-render`
(defonce disable-request-render? (atom false))

(defn module-ready?
  []
  (and internal-module (fn? (unchecked-get internal-module "_init"))))

(defn live?
  "GL/WASM context exists and is not marked lost. True during reload after re-init."
  []
  (and context-initialized? (not @context-lost?)))

(defn ready?
  "Safe for normal application WASM calls (not lost, not mid-reload)."
  []
  (and (live?) (not @reloading?)))

(defn reset-context-state!
  "Clears canvas/GL handles and marks the context uninitialized.

  Intentionally does **not** clear `context-lost?` or `reloading?`. During a
  context-restore reload, `clear-canvas` runs while recovery is still in
  progress; clearing those flags here would reopen a window where callers think
  WebGL is ready before re-init finishes."
  []
  (set! internal-frame-id nil)
  (set! internal-frame-type 0)
  (set! canvas nil)
  (set! canvas-snapshot nil)
  (set! gl-context-handle nil)
  (set! gl-context nil)
  (set! context-initialized? false))

(defonce serializers nil)

(defn init-serializers!
  "Binds the enum table produced by the `enums/serializers` macro."
  [table]
  (let [missing (array)]
    (doseq [key (js/Object.keys table)]
      (when (undefined? (unchecked-get table key))
        (.push missing key)))

    (when (pos? (alength missing))
      (throw (ex-info "stale or incomplete render-wasm shared.js"
                      {:missing (vec missing)})))

    (set! serializers table)))

