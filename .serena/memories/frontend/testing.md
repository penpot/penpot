# Frontend Testing and Live Verification

Frontend validation: CLJS + React/Rumext + RxJS/Potok; SCSS modules; shared CLJC from `common/`.

## Unit tests

READ `mem:testing` FIRST — it defines the execution discipline (no piping, tee to file, preferred commands) that applies to all CLJS/JS test runs.

Frontend unit tests live under `frontend/test/frontend_tests/` and use `cljs.test`. They should be deterministic, avoid DOM/UI integration where possible, and mock side effects such as RPC, storage, timers, or network access.

### Async-first stance

Frontend testing is async-first: everything essentially asynchronous is modeled with a test reproducing the asynchrony, even when the test could be written "synchronously". Sync-passing tests prove nothing about async behavior and rot as soon as an async boundary appears downstream. Consequences: mock through `frontend-tests.helpers.mock`, never `with-redefs`, except unit tests of purely synchronous functions; transport doubles deliver asynchronously (`observe-on :async`) while the test keeps scenario timing (explicit pushes); assertions always follow quiescence (`wait-for` on presence, bare `settle` tick for absence-only blocks), never a trigger.

### Primitives

- `mock/with-mocks` (callback style, legacy compat): installs with `set!` so mocks survive async boundaries; bodies run deferred past the current tick via `asap`, so only done-chained (`t/async`) contexts are allowed; `done'` restores and completes exactly once (twice only warns; never calling it stalls the run and leaks the mocks). Prefer `mock/with-mocks*` for new tests.
- `mock/with-mocks*` (direction): body forms wrapped in a generated `^:async` fn, evaluates to a promise — `await` it, `await` nested scopes too, no `done` in test code. Rejections and non-promise returns report as `:error` via `run-mocked`.
- `mock/stub` wraps fns for arities 0-6 (the `:esm` test build dispatches multi-arity vars as `cljs$core$IFn$_invoke$arity$N`); when the mocked var is variadic-defined (or called with more than 6 args), the call compiles to variadic dispatch, so use a plain variadic `fn` — the stub does not forward variadic. A mock must not call the mocked var again (self-delegation inside a multi-arity function recurses).
- Helpers in `frontend-tests.helpers.async`: `->promise` (single-value observable → promise; beicon has no `to-promise`), `await-response` (subscribe→push→await, atomic), `settle`, `wait-for`, `wait-for-store`, `observe` (stream → termination promise; asserts provided, timeout rejects).
- `wait-for` waits on a **signal**, not on turns: `:signal` (an observable) re-checks the predicate on every emission and resolves on the event that changes the state; `:timeout-ms` (default 5000) only bounds failure then. Without a signal it polls against a real-time deadline — 50 turns were ~54 ms idle, and turns are not a unit of time. `wait-for-store` passes `ptk/input-stream` as the signal (the store notifies after applying the update, so the predicate sees the new state); the persistence fixture exposes `requests$` for `(= N (count @requests))`. A wait that resolves on an event consumes no turn. The waits with nothing to listen to stay on the poll: test atoms filled outside the store (`@cycles`, `@reports`, `@done`), `@failures`, and predicates over JS objects or closures.
- Fixtures return promises (`with-watchdog`, `with-persistence`); `await` them from `^:async` tests. `main_errors` and `fonts` are fully migrated (no legacy `with-mocks` left).
- Valid `^:async` placements: `mem:clojure/idioms`.

### Observing event streams

To assert over emitted event sequences, observe termination: subscribe through `observe` (async delivery forced even for sync sources), `await` its promise, then assert the collected values. Never branch on nil (`when-let` skipping observation lets setup bugs pass as "empty"): producers answer refusals with empty streams, never nil, so every path subscribes uniformly. Observed termination is exact quiescence — no manual `settle` after it. Errors reject unless `:on-error` handles them.

### Runner and library facts (verified: CLJS 1.12.145, beicon2 `df7058a`)

- `cljs.test` keeps its env in a `set!` var: assertions inside deferred ticks count. `run-block`: double `done` only warns; missing `done` stalls.
- `t/async` discards the body promise — completion signals ONLY via `done`. `t/deftest ^:async` adds auto async-context + auto `done`, but awaiting stays the author's job; without it the test passes empty.
- `take 1` is per-subscription on a hot subject: subscribe-before-push or hang. `end!`/`.error` with pending takes only forwards valueless completion.

### Traps that bit

- `^:async` tests require map-style fixtures (`(t/use-fixtures :each {:before f})`): function-style fixtures abort the whole run ("Async tests require fixtures to be specified as maps").
- `st/emit!` doubles collect heterogeneous events: audit `DataEvent`s deref, toast reify-objects do not — discriminate with `ptk/type` (total, never throws), never blind `deref`.
- Subscription order decides delivery order: never resolve settlement from a pre-subscribed branch racing the pipeline.
- Auto-answering mocks lose deadline expressiveness (can't time answers), need teardown timer-cancellation, and post-teardown deliveries hit real implementations — explicit pushes + async delivery + `wait-for` won on every axis.
- A wait that observes an effect the test asserts takes a signal. A wait that means "the side effect advanced before the next emit" has no event to listen to: keep the poll (or an explicit `settle`) and say so, because a signal would resolve inside the same `emit!` and the next step would overtake the pipeline (`persistence-rollback-test` drove its four emits this way).
- With `rp/cmd!` mocked, a store-state wait can resolve before the flow that changes it ends, and the mock scope then closes in flight: the next call hits the real HTTP layer (malformed URL from a nil `cf/public-uri`) and the error is reported on a later test. `unlink-file-from-library` dissocs the library before its async chain, so the only proof the chain ran inside the mock is its second RPC call; wait for that, through the mock.
- Teardown belongs to the terminal continuation, never to `finally`-around-triggers (it would dispose in-flight flows).
- A body that awaits must be `(^:async fn …)` even if the rest is sync; sync sequences are atomic vs the event loop.
- `app.main.errors` prints every handled cause with `ex/print-throwable` before notifying, and the app logger does not gate it. A test that runs a real handler end to end stubs that var (`main-errors-test/quiet-throwable`): without it, the 10 000-error governor test alone wrote 190 k of the 192 k lines of a run log (13,8 MB), and GitHub truncates a step log past ~4 MB, cutting the summary and any failing assertion.
- The `:esm` test build cannot load the CommonJS `sax` module behind `tubax`, so SVG parsing fails in tests (`dwm/valid-svg-string?` answers false, `dwm/svg->clj` errors): mock both with the parsed data.

From `frontend/`:
- Full unit test run (always builds, suppressed output): `pnpm run test:quiet`.
- Full unit test run (always builds, build output visible): `pnpm run test`.
- Focus a frontend CLJS test namespace: `pnpm run test:quiet -- --focus frontend-tests.logic.components-and-tokens`.
- Focus one frontend CLJS test var: `pnpm run test:quiet -- --focus frontend-tests.logic.components-and-tokens/change-spacing-token-in-main-updates-copy-layout`.
- Quiet `app.*` logging during a run: append `--log-level warn` (or `trace|debug|info|warn|error`).
- Build test target only (no run): `pnpm run build:test`.
- After `build:test` has been run, run the compiled runner directly: `node target/tests/test.js [--focus ...] [--log-level ...]`.
- Watch tests: `pnpm run watch:test`.

New frontend test namespaces must be required/listed in `frontend_tests/runner.cljs`; new vars in existing namespaces need no runner change.

## Playwright integration tests

Do not add, modify, or run Playwright integration tests under `frontend/playwright` unless explicitly asked. When explicitly asked, use `pnpm run test:e2e` or `pnpm run test:e2e --grep "pattern"` from `frontend/`; ensure dependencies are installed through `./scripts/setup` if the environment is not prepared.

Integration tests fake backend behavior by intercepting network/websocket traffic, so every RPC or websocket the page needs must be mocked. Use existing Page Object Models:
- `BasePage.mockRPC` intercepts RPC calls and already prefixes `/api/rpc/command/`; pass command names such as `get-profile`, not full URLs.
- Workspace or other websocket-using pages should extend/use `BaseWebSocketPage`, initialize websocket mocks before each test, and mock `/ws/notifications` with the provided helpers.
- Prefer common locators/actions in POMs; ad-hoc locators can stay in a single test.

Locator priority should follow user-facing semantics: `getByRole`, `getByLabel`, `getByPlaceholder`, `getByText`, then semantic alternatives such as alt/title, with `getByTestId` as the last resort. Name tests from the user's perspective and prefer positive, single-purpose assertions.

## CI (E2E)

`.github/workflows/tests-e2e.yml` runs the integration specs, the composable component suite, and the mocked Plugin API suite from one workflow that builds the frontend bundle once per SHA. Before adding a job that needs the bundle, read `mem:frontend/e2e-ci-workflow` (build-once contract, cache key, stable check names).

## Live browser verification

Because CLJC compiles to both JVM and CLJS, JVM/common tests can miss frontend-only state caused by browser runtime, WASM modifier math, or real pointer events. Use `mem:frontend/cljs-repl` to inspect live app state and `mem:frontend/playwright-gestures` when real input is needed.

For stale hot reload or failed CLJ/CLJC/CLJS source builds, read `mem:frontend/compile-diagnostics`. For Internal Error pages or delayed runtime crashes after automation/API actions, read `mem:frontend/handling-crashes`. Translation `.po` changes are bundled into `index.html` and require a browser refresh.
