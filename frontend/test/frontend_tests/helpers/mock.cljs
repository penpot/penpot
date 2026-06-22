;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers.mock
  "Async-first mocking primitives for ClojureScript tests.

  Uses `with-redefs` — the standard CLJS mechanism for rebinding Vars
  within a dynamic scope.  Recording atoms (`rpc-calls`, `revoked-uris`)
  persist across the entire test lifecycle, making captured data
  inspectable regardless of whether callbacks fire synchronously or
  asynchronously.

  The `with-mocks` helper wraps the lifecycle:
    1. Reset recording atoms
    2. Install mocks via `with-redefs`
    3. Execute `(test-fn inner-done)`
    4. `inner-done` calls `outer-done` (typically `cljs.test/async`'s done)

  Since all mock functions return synchronous `rx/of` observables,
  callbacks always fire within the `with-redefs` body."
  (:require
   [beicon.v2.core :as rx]))

;; ═══════════════════════════════════════════════════════════════
;; Recording atoms
;; ═══════════════════════════════════════════════════════════════

(def rpc-calls
  "Atom accumulating mocked `rp/cmd!` calls as `{:cmd kw :params map}`."
  (atom []))

(def revoked-uris
  "Atom accumulating URIs passed to `wapi/revoke-uri`."
  (atom []))

;; ═══════════════════════════════════════════════════════════════
;; Mock implementations
;; ═══════════════════════════════════════════════════════════════

(defn rpc-cmd!-mock
  "Records [cmd params] in [[rpc-calls]], returns `(rx/of nil)`."
  [cmd params]
  (swap! rpc-calls conj {:cmd cmd :params params})
  (rx/of nil))

(defn revoke-uri-mock
  "Records `uri` in [[revoked-uris]]."
  [uri]
  (swap! revoked-uris conj uri))

(defn schedule-on-idle-mock
  "Calls `f` immediately instead of deferring to the idle queue."
  [f]
  (f))

(defn timer-mock
  "Returns `(rx/of :immediate)` so debounce timers fire instantly
  during tests."
  [_ms]
  (rx/of :immediate))

;; ═══════════════════════════════════════════════════════════════
;; Lifecycle
;; ═══════════════════════════════════════════════════════════════

(defn reset!
  "Clear all recording atoms.  Called automatically by [[with-mocks]]."
  []
  (reset! rpc-calls [])
  (reset! revoked-uris []))

;; ═══════════════════════════════════════════════════════════════
;; Public API
;; ═══════════════════════════════════════════════════════════════

(defn with-mocks
  "Resets recording atoms, installs `mocks` via `with-redefs`, then
  calls `(test-fn inner-done)`.

  `mocks` is a map of `Var → mock-fn` (e.g. `{#'rp/cmd! mock-fn}`).
  `inner-done` tears down the `with-redefs` (by returning) and calls
  `outer-done` (the `cljs.test/async` `done` callback).

  Example:

      (t/deftest my-async-test
        (t/async done
          (mock/with-mocks
            {#'rp/cmd! mock/rpc-cmd!-mock}
            (fn [done']
              (->> (some-async-flow)
                   (rx/subs!
                     (fn [v] ...)
                     (fn [err] (done'))
                     (fn [] (done'))))))))"
  [mocks test-fn outer-done]
  (reset!)
  (apply with-redefs (mapcat identity mocks)
         (test-fn (fn inner-done []
                    (outer-done)))))
