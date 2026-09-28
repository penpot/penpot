;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.helpers.mock
  "Async-first mocking primitives for ClojureScript tests.

  Uses `set!` to install and restore mocks so that they remain active
  across asynchronous boundaries (Promises, RxJS subscriptions, callbacks).
  `with-redefs` cannot be used here because it restores bindings when the
  body exits, which is too early for async code.

  Recording atoms (`rpc-calls`, `revoked-uris`) persist across the entire
  test lifecycle, making captured data inspectable regardless of whether
  callbacks fire synchronously or asynchronously.

  The `with-mocks` helper wraps the lifecycle:
    1. Reset recording atoms
    2. Save original var values, install mocks via `set!`
    3. Defer `(test-fn inner-done)` past the current tick via `asap`,
       so the body provably runs with mocks that survived an async
       boundary
    4. `inner-done` restores originals and calls `outer-done`
       (typically `cljs.test/async`'s done). A throw inside the deferred
       body is reported as an `:error`, then restores and completes.

  Requires a done-chained context (usually `t/async`): a synchronous
  test completes before its deferred body runs.

  Usage: `(with-mocks {ns/sym mock-fn, ...} test-fn done)`"
  #?(:cljs (:require
            [beicon.v2.core :as rx]
            [cljs.test :as t]))
  #?(:cljs (:require-macros [frontend-tests.helpers.mock])))

;; ═══════════════════════════════════════════════════════════════
;; Macro (compile-time, Clojure only)
;; ═══════════════════════════════════════════════════════════════

#?(:clj
   (defmacro with-mocks
     "Resets recording atoms, installs `mocks` via `set!`, then defers
     `(test-fn inner-done)` past the current tick via `asap`.

     `mocks` is a map of sym → mock-fn
     (e.g. `{app.main.repo/cmd! mock-fn}`).

     `inner-done` restores the originals and calls `outer-done` (the
     `cljs.test/async` `done` callback). A throw inside the deferred body
     is reported as an `:error` before restoring and completing, so a
     failing body can neither leak the mocks nor stall the run.

     Requires a done-chained context (usually `t/async`): a synchronous
     caller completes before its deferred body runs.

     Example:

         (t/deftest my-async-test
           (t/async done
             (mock/with-mocks
               {app.main.repo/cmd! mock/rpc-cmd-mock}
               (fn [done']
                 (->> (some-async-flow)
                      (rx/subs!
                        (fn [v] ...)
                        (fn [err] (done'))
                        (fn [] (done')))))
               done)))"
     [mocks test-fn outer-done]
     (let [entries   (map identity mocks)
           gen-pairs (mapv (fn [[qsym _mock]]
                             {:qsym qsym
                              :osym  (gensym "orig-")})
                           entries)
           ;; [orig-123 app.main.repo/cmd!, orig-456 app.util.timers/schedule-on-idle, ...]
           let-bindings (vec (mapcat
                              (fn [{:keys [qsym osym]}]
                                [osym qsym])
                              gen-pairs))
           install-exprs (mapv
                          (fn [[_qsym mock-fn] {:keys [qsym]}]
                            `(set! ~qsym ~mock-fn))
                          entries
                          gen-pairs)
           restore-exprs (mapv
                          (fn [{:keys [qsym osym]}]
                            `(set! ~qsym ~osym))
                          gen-pairs)]
       `(let [test-fn# ~test-fn]
          (frontend-tests.helpers.mock/reset-state!)
          (let ~let-bindings
            ~@install-exprs
            (frontend-tests.helpers.mock/asap
             (fn []
               (try
                 (test-fn# (fn []
                             ~@restore-exprs
                             (~outer-done)))
                 (catch :default e#
                   ~@restore-exprs
                   (cljs.test/report {:type :error
                                      :message "Uncaught exception, not in assertion."
                                      :expected nil
                                      :actual e#})
                   (~outer-done))))))))))

#?(:clj
   (defmacro with-mocks*
     "Installs `mocks` via `set!`, then evaluates `body` inside a generated
     `^:async` fn awaited via `run-mocked`: when the body settles, the
     originals are restored — always. A rejection is reported as an `:error`.

     Evaluates to a promise resolving once the body settles and the mocks
     are restored. `await` it, either directly or by returning it from an
     `^:async` fn; nested scopes must be awaited too.

     Example:

         (t/deftest ^:async my-async-test
           (await (mock/with-mocks*
                    {app.main.repo/cmd! mock/rpc-cmd-mock}
                    (await (some-async-flow))
                    ...)))"
     [mocks & body]
     (let [entries   (map identity mocks)
           gen-pairs (mapv (fn [[qsym _mock]]
                             {:qsym qsym
                              :osym  (gensym "orig-")})
                           entries)
           let-bindings (vec (mapcat
                              (fn [{:keys [qsym osym]}]
                                [osym qsym])
                              gen-pairs))
           install-exprs (mapv
                          (fn [[_qsym mock-fn] {:keys [qsym]}]
                            `(set! ~qsym ~mock-fn))
                          entries
                          gen-pairs)
           restore-exprs (mapv
                          (fn [{:keys [qsym osym]}]
                            `(set! ~qsym ~osym))
                          gen-pairs)]
       `(do
          (frontend-tests.helpers.mock/reset-state!)
          (let ~let-bindings
            ~@install-exprs
            (frontend-tests.helpers.mock/run-mocked
             (^:async fn [] ~@body)
             (fn [] ~@restore-exprs)))))))

;; ═══════════════════════════════════════════════════════════════
;; Runtime (ClojureScript only)
;; ═══════════════════════════════════════════════════════════════

#?(:cljs
   (do
     ;; Recording atoms
     ;; ═══════════════════════════════════════════════════════════════

     (def rpc-calls
       "Atom accumulating mocked `rp/cmd!` calls as `{:cmd kw :params map}`."
       (atom []))

     (def revoked-uris
       "Atom accumulating URIs passed to `wapi/revoke-uri`."
       (atom []))

     ;; Mock implementations
     ;; ═══════════════════════════════════════════════════════════════

     (defn rpc-cmd-mock
       "Records [cmd params] in [[rpc-calls]], returns `(rx/of nil)`."
       ([cmd params]
        (swap! rpc-calls conj {:cmd cmd :params params})
        (rx/of nil))
       ([cmd params _opts]
        (swap! rpc-calls conj {:cmd cmd :params params})
        (rx/of nil)))

     (defn revoke-uri-mock
       "Records `uri` in [[revoked-uris]]."
       [uri]
       (swap! revoked-uris conj uri))

     (defn schedule-on-idle-mock
       "Calls `f` immediately instead of deferring to the idle queue."
       ([_ms f]
        (f))
       ([f]
        (f)))

     (defn timer-mock
       "Returns `(rx/of :immediate)` so debounce timers fire instantly
       during tests."
       [_ms]
       (rx/of :immediate))

     ;; Static-dispatch-safe stubs
     ;; ═══════════════════════════════════════════════════════════════
     ;;
     ;; The `:esm` test build compiles calls to a *multi-arity* var as
     ;; `f.cljs$core$IFn$_invoke$arity$N(...)`. A plain single-arity `fn`
     ;; (including `identity`) does not expose that property, so using one
     ;; to redefine such a var throws "arity$N is not a function". Multi-arity
     ;; fns do expose the property, hence the helpers below.

     (defn noop
       "Multi-arity no-op. Use to stub static-dispatched multi-arity vars
       such as `st/emit!` (replacing `identity`, which is single-arity)."
       ([] nil)
       ([_] nil)
       ([_ _] nil)
       ([_ _ _] nil)
       ([_ _ _ _] nil)
       ([_ _ _ _ & _] nil))

     (defn stub
       "Wraps `f` in a multi-arity fn (arities 0-6) delegating to `f`, so the
       result exposes `cljs$core$IFn$_invoke$arity$N`. Required when replacing
       a multi-arity var in a `with-redefs`/`set!` mock with a capturing fn."
       [f]
       (fn
         ([] (f))
         ([a] (f a))
         ([a b] (f a b))
         ([a b c] (f a b c))
         ([a b c d] (f a b c d))
         ([a b c d e] (f a b c d e))
         ([a b c d e g] (f a b c d e g))))

     ;; Scheduling
     ;; ═══════════════════════════════════════════════════════════════

     (defn asap
       "Runs `f` on the next macrotask (`js/setTimeout` 0) and returns
        the timer id. Used by `with-mocks` to defer the test body past
        the current tick, so installed mocks provably survive async
        boundaries."
       [f]
       (js/setTimeout f 0))

     (defn ^:async run-mocked
       "Awaits the async zero-arg `thunk`, then runs `restore` — always, in
        that order — and resolves. A rejection is reported as an `:error`
        (uncaught-exception semantics, like `test-var-block*`); a non-promise
        return is reported as an `:error` too."
       [thunk restore]
       (let [p (thunk)]
         (if (and (some? p) (fn? (.-then p)))
           (try
             (await p)
             (catch :default e
               (t/report {:type :error
                          :message "Uncaught exception, not in assertion."
                          :expected nil
                          :actual e}))
             (finally
               (restore)))
           (do
             (restore)
             (t/report {:type :error
                        :message "with-mocks* body did not return a promise."
                        :expected nil
                        :actual p})))))

     ;; Lifecycle
     ;; ═══════════════════════════════════════════════════════════════

     (defn reset-state!
       "Clear all recording atoms.  Called automatically by [[with-mocks]]."
       []
       (reset! rpc-calls [])
       (reset! revoked-uris []))))
