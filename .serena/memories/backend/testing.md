# Backend testing

- **Read `mem:testing` first** for cross-cutting test design and output discipline. Backend uses Kaocha and discovers namespaces under `backend/test/backend_tests/` automatically.
- From the repo root, `./scripts/ci backend` runs lint, tests and format; `./scripts/ci --test backend` runs only tests. Both append `enable-backend-asserts` to `PENPOT_FLAGS`. From `backend/`, direct and focused runs must append the flag too: `PENPOT_FLAGS="${PENPOT_FLAGS:+$PENPOT_FLAGS }enable-backend-asserts" clojure -M:dev:test`. Assertions are selected when `app.config` loads, before fixtures change flags.
- Kaocha accepts repeated `--focus` flags for namespaces or vars. An Integrant schema assertion during test-system construction is a load/build error, not a failed test assertion; see `mem:backend/integrant-schemas`.
- Use the workspace's existing `PENPOT_TEST_*` database and Redis URIs, not CI values copied from logs. Confirm the destination before changing it; see `mem:devenv/core`. Test databases can have newer migrations, but never drop or recreate one without explicit permission.
- Redirect test output to a file before inspecting or filtering it. For coverage, `clojure -M:dev:test:coverage` instruments all `app.*` namespaces even on focused runs; it is slower and produces reflection warnings. Output in `target/coverage/` includes lcov, HTML and EMMA form counts; use normal tests for routine checks.

## Test system and helpers

- `backend-tests.helpers` builds the system from `main/system-config` and `main/worker-config`, excluding HTTP, srepl, cron, dispatcher, audit consumer loops and every worker runner. Live runners or consumers race tests that drive jobs or audit passes manually. Keep runner removal by key shape, not a fixed profile list.
- Those configs are `def`s evaluated at namespace load against the real environment; runtime `cf/get` inside tests can see the test-prefixed config instead. A tenant mismatch can silently send a component and test to different Redis keys.
- `th/run-task!` runs a job by name, not an audit consumer. For consumers call `(consumers/run-pass cfg consumer)` with pool, metrics and transport dependencies; nil means success, while `::consumers/busy` and `::consumers/disabled` mean no work started.
- `app.loggers.audit/submit` propagates failures. DB-free tests that reach audit emission need a stub or a cfg with `::db/pool`; the shared fixture stubs it.
- RPC helpers `command!` and `management-command!` split qualified keys into server params and unqualified keys into the request body. Pass request-level context in `:app.http/request` metadata; non-map request stubs fall back to a dummy request.
- `metrics-stub` provides no-op collectors. Use `with-climit` or explicitly halt the limiter created by `init-climit`: it starts a republisher thread.
- Do not assert log lines, hints or props. Logs are not test contracts; assert the returned value, event or metric that must remain stable instead.
