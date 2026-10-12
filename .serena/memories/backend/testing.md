# Backend Testing

JVM `clojure.test` (kaocha runner) under `backend/test/backend_tests/`.

- READ `mem:testing` FIRST — it defines the execution discipline (no piping, tee to file, preferred commands) that applies to all JVM test runs.
- All CLI commands must be executed from the `backend/` subdirectory.
- Tests are invoked directly via `clojure -M:dev:test` (kaocha) — there is no pnpm wrapper. Kaocha auto-discovers test namespaces, so no runner registration is needed.
- Coverage: if code is added or modified in `src/`, corresponding tests in `test/backend_tests/` must be added or updated.
- Isolated run: `clojure -M:dev:test --focus backend-tests.my-ns-test` for a specific test namespace, or `clojure -M:dev:test --focus backend-tests.my-ns-test/my-test-var` for a specific test var.
- Regression run: `clojure -M:dev:test` to ensure no regressions in related functional areas.
- If you need to filter output, tee to a temp file first: `clojure -M:dev:test 2>&1 | tee /tmp/penpot-test-output.txt`.
- `backend-tests.helpers` has `metrics-stub` (a metrics instance with no collectors, where every `run!` is a no-op), `init-climit` (a concurrency limiter built from an inline config map, with an optional second argument for the metrics instance to publish to) and `with-climit`, the macro that builds one, runs the body and halts it. `init-climit` starts the republisher daemon thread, so a test that builds a limiter without halting it leaks that thread for the rest of the JVM run: use `with-climit`. Log lines are capturable with `add-watch` on `app.common.logging/log-record` (it holds the last record; the watcher sees every one, but only of the levels the logger has enabled: a line the app writes at TRACE never gets there).