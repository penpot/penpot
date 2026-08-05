# Frontend Testing and Live Verification

Frontend validation: CLJS + React/Rumext + RxJS/Potok; SCSS modules; shared CLJC from `common/`.

## Unit tests

READ `mem:testing` FIRST — it defines the execution discipline (no piping, tee to file, preferred commands) that applies to all CLJS/JS test runs.

Frontend unit tests live under `frontend/test/frontend_tests/` and use `cljs.test`. They should be deterministic, avoid DOM/UI integration where possible, and mock side effects such as RPC, storage, timers, or network access.

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

## Live browser verification

Because CLJC compiles to both JVM and CLJS, JVM/common tests can miss frontend-only state caused by browser runtime, WASM modifier math, or real pointer events. Use `mem:frontend/cljs-repl` to inspect live app state and `mem:frontend/playwright-gestures` when real input is needed.

For stale hot reload or failed CLJ/CLJC/CLJS source builds, read `mem:frontend/compile-diagnostics`. For Internal Error pages or delayed runtime crashes after automation/API actions, read `mem:frontend/handling-crashes`. Translation `.po` changes are bundled into `index.html` and require a browser refresh.
