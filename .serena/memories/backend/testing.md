# Backend Testing

JVM `clojure.test` (kaocha runner) under `backend/test/backend_tests/`.

- READ `mem:testing` FIRST — it defines the execution discipline (no piping, tee to file, preferred commands) that applies to all JVM test runs.
- All CLI commands must be executed from the `backend/` subdirectory.
- Tests are invoked directly via `clojure -M:dev:test` (kaocha) — there is no pnpm wrapper. Kaocha auto-discovers test namespaces, so no runner registration is needed.
- **Preferred way to run them: `scripts/ci <module> --test`**, which runs lint, tests and format per module and writes a log per task under `.ci-logs/`. It exports `PENPOT_FLAGS` with `enable-backend-asserts` appended, so use it rather than calling `clojure -M:dev:test` by hand.
- **Why the flag matters:** `clojure.core/assert` is elided at compile time when `*assert*` is false, and `app.config` only sets that var from the flags when it loads. The test fixture redefines the flags after that, so they never reach it: without the flag the suite runs with every assertion compiled out, and a test that depends on an assert only means something with it set. GitHub Actions sets it too. When running the suite directly, the command is `PENPOT_FLAGS="enable-backend-asserts" clojure -M:dev:test`.
- Coverage: if code is added or modified in `src/`, corresponding tests in `test/backend_tests/` must be added or updated.
- Isolated run: `clojure -M:dev:test --focus backend-tests.my-ns-test` for a specific test namespace, or `clojure -M:dev:test --focus backend-tests.my-ns-test/my-test-var` for a specific test var.
- Multiple focus symbols are supported by REPEATING the flag (kaocha accumulates them, last-wins does not apply): `clojure -M:dev:test --focus backend-tests.ns-one --focus backend-tests.ns-two` — preferred over a shell loop of isolated runs.
- Regression run: `clojure -M:dev:test` to ensure no regressions in related functional areas.
- If you need to filter output, tee to a temp file first: `clojure -M:dev:test 2>&1 | tee /tmp/penpot-test-output.txt`.
- RPC test helpers `command!`/`management-command!` split the data map: qualified keys become server params, unqualified keys become request body params. To inject request-level context (headers, `:app.http/auth-key-id`, ip), pass a map under `:app.http/request` metadata; non-map `IRequest` stubs fall back to a dummy request.