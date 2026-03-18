;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers.wasm
  "Test helpers for mocking WASM API boundary functions.

   In the Node.js test environment the WASM binary is not available,
   but the `render-wasm/v1` feature flag is enabled by default, so
   every geometry-modifying event takes the WASM code path.
   This namespace provides lightweight mock implementations that let
   the Clojure-side logic execute normally while stubbing out every
   call that would touch the WASM heap.

   Each mock tracks how many times it was called via `call-counts`.
   Use `(call-count :propagate-modifiers)` in test assertions to
   verify the WASM code path was exercised."
  (:require
   [app.common.data :as d]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.api.fonts :as wasm.fonts]))

;; --- Call tracking ---------------------------------------------------

(def ^:private call-counts
  "Atom holding a map of mock-name → number of calls since last reset."
  (atom {}))

(defn- track!
  "Increment the call count for `mock-name`."
  [mock-name]
  (swap! call-counts update mock-name (fnil inc 0)))

(defn call-count
  "Return how many times mock `mock-name` was called since setup.
   `mock-name` is a keyword, e.g. `:propagate-modifiers`."
  [mock-name]
  (get @call-counts mock-name 0))

(defn reset-call-counts!
  "Reset all call counts to zero."
  []
  (reset! call-counts {}))

;; --- Mock implementations --------------------------------------------

(defn- mock-propagate-modifiers
  "Passthrough mock for `wasm.api/propagate-modifiers`.

  Receives `entries` — a vector of `[uuid {:transform matrix, :kind kw}]`
  pairs produced by `parse-geometry-modifiers` — and returns a vector
  of `[uuid matrix]` pairs that `apply-wasm-modifiers` converts to a
  transforms map via `(into {} result)`.

  This effectively tells the caller \"apply exactly the transform that
  was requested\", which is what the real WASM engine does for simple
  moves / resizes without constraints."
  [entries _pixel-precision]
  (track! :propagate-modifiers)
  (when (d/not-empty? entries)
    (into []
          (map (fn [[id data]]
                 (d/vec2 id (:transform data))))
          entries)))

(defn- mock-clean-modifiers
  []
  (track! :clean-modifiers)
  nil)

(defn- mock-set-structure-modifiers
  [_entries]
  (track! :set-structure-modifiers)
  nil)

(defn- mock-set-shape-grow-type
  [_grow-type]
  (track! :set-shape-grow-type)
  nil)

(defn- mock-set-shape-text-content
  [_shape-id _content]
  (track! :set-shape-text-content)
  nil)

(defn- mock-set-shape-text-images
  ([_shape-id _content]
   (track! :set-shape-text-images)
   nil)
  ([_shape-id _content _thumbnail?]
   (track! :set-shape-text-images)
   nil))

(defn- mock-get-text-dimensions
  ([]
   (track! :get-text-dimensions)
   {:x 0 :y 0 :width 100 :height 20 :max-width 100})
  ([_id]
   (track! :get-text-dimensions)
   {:x 0 :y 0 :width 100 :height 20 :max-width 100}))

(defn- mock-font-stored?
  [_font-data _emoji?]
  (track! :font-stored?)
  true)

(defn- mock-make-font-data
  [font]
  (track! :make-font-data)
  {:wasm-id 0
   :weight (or (:font-weight font) "400")
   :style (or (:font-style font) "normal")
   :emoji? false})

(defn- mock-get-content-fonts
  [_content]
  (track! :get-content-fonts)
  [])

;; --- Persistent mock installation via `set!` --------------------------
;;
;; These use `set!` to directly mutate the module-level JS vars, making
;; the mocks persist across async boundaries. They are intended to be
;; used with `t/use-fixtures :each` which correctly sequences `:after`
;; to run only after the async test's `done` callback fires.

(def ^:private originals
  "Stores the original WASM function values so they can be restored."
  (atom {}))

(defn setup-wasm-mocks!
  "Install WASM mocks via `set!` that persist across async boundaries.
   Also resets call counts. Call `teardown-wasm-mocks!` to restore."
  []
  ;; Reset call tracking
  (reset-call-counts!)
  ;; Save originals
  (reset! originals
          {:clean-modifiers         wasm.api/clean-modifiers
           :set-structure-modifiers wasm.api/set-structure-modifiers
           :propagate-modifiers     wasm.api/propagate-modifiers
           :set-shape-grow-type     wasm.api/set-shape-grow-type
           :set-shape-text-content  wasm.api/set-shape-text-content
           :set-shape-text-images   wasm.api/set-shape-text-images
           :get-text-dimensions     wasm.api/get-text-dimensions
           :font-stored?            wasm.fonts/font-stored?
           :make-font-data          wasm.fonts/make-font-data
           :get-content-fonts       wasm.fonts/get-content-fonts})
  ;; Install mocks
  (set! wasm.api/clean-modifiers         mock-clean-modifiers)
  (set! wasm.api/set-structure-modifiers mock-set-structure-modifiers)
  (set! wasm.api/propagate-modifiers     mock-propagate-modifiers)
  (set! wasm.api/set-shape-grow-type     mock-set-shape-grow-type)
  (set! wasm.api/set-shape-text-content  mock-set-shape-text-content)
  (set! wasm.api/set-shape-text-images   mock-set-shape-text-images)
  (set! wasm.api/get-text-dimensions     mock-get-text-dimensions)
  (set! wasm.fonts/font-stored?          mock-font-stored?)
  (set! wasm.fonts/make-font-data        mock-make-font-data)
  (set! wasm.fonts/get-content-fonts     mock-get-content-fonts))

(defn teardown-wasm-mocks!
  "Restore the original WASM functions saved by `setup-wasm-mocks!`."
  []
  (let [orig @originals]
    (set! wasm.api/clean-modifiers         (:clean-modifiers orig))
    (set! wasm.api/set-structure-modifiers (:set-structure-modifiers orig))
    (set! wasm.api/propagate-modifiers     (:propagate-modifiers orig))
    (set! wasm.api/set-shape-grow-type     (:set-shape-grow-type orig))
    (set! wasm.api/set-shape-text-content  (:set-shape-text-content orig))
    (set! wasm.api/set-shape-text-images   (:set-shape-text-images orig))
    (set! wasm.api/get-text-dimensions     (:get-text-dimensions orig))
    (set! wasm.fonts/font-stored?          (:font-stored? orig))
    (set! wasm.fonts/make-font-data        (:make-font-data orig))
    (set! wasm.fonts/get-content-fonts     (:get-content-fonts orig)))
  (reset! originals {}))

(defn with-wasm-mocks*
  "Calls `(thunk)` with all WASM API boundary functions replaced by
   safe mocks, restoring the originals when the thunk returns.

   NOTE: Teardown happens synchronously when `thunk` returns. For
   async tests (e.g. those using `tohs/run-store-async`), use
   `setup-wasm-mocks!` / `teardown-wasm-mocks!` via
   `t/use-fixtures :each` instead."
  [thunk]
  (setup-wasm-mocks!)
  (try
    (thunk)
    (finally
      (teardown-wasm-mocks!))))
