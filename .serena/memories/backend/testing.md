# Backend Testing

JVM `clojure.test` (kaocha runner) under `backend/test/backend_tests/`.

- READ `mem:testing` FIRST — it defines the execution discipline (no piping, tee to file, preferred commands) that applies to all JVM test runs.
- All CLI commands must be executed from the `backend/` subdirectory.
- Tests are invoked directly via `clojure -M:dev:test` (kaocha) — there is no pnpm wrapper. Kaocha auto-discovers test namespaces, so no runner registration is needed.
- Coverage: if code is added or modified in `src/`, corresponding tests in `test/backend_tests/` must be added or updated.
- Isolated run: `clojure -M:dev:test --focus backend-tests.my-ns-test` for a specific test namespace, or `clojure -M:dev:test --focus backend-tests.my-ns-test/my-test-var` for a specific test var.
- Regression run: `clojure -M:dev:test` to ensure no regressions in related functional areas.
- If you need to filter output, tee to a temp file first: `clojure -M:dev:test 2>&1 | tee /tmp/penpot-test-output.txt`.