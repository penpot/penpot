# Backend Testing

JVM `clojure.test` (kaocha runner) under `backend/test/backend_tests/`.

- READ `mem:testing` FIRST — it defines the execution discipline (no piping, tee to file, preferred commands) that applies to all JVM test runs.
- Test DB/Redis come from the `PENPOT_TEST_*` env vars already set in the shell (`PENPOT_TEST_DATABASE_URI`, `PENPOT_TEST_REDIS_URI`, ...); per instance they point at `penpot_test` / `penpot_test_wsN` and valkey DB `6+N` (`mem:devenv/core`). Do not override them with values copied from CI logs or other sources unless the user asks (e.g. "use a temporary database"); the CI env (`redis://redis/1`, `postgresql://postgres/penpot_test`) only works inside the CI docker network.
- The test database may have migrations applied from newer versions (it is shared dev infra). Reinitializing it — `dropdb`/`createdb` of the `PENPOT_TEST_*` database only — is explicitly permitted when that blocks a test run; never drop the main `penpot` database or another instance's test database.
- All CLI commands must be executed from the `backend/` subdirectory.
- Kaocha auto-discovers test namespaces, so no runner registration is needed.
- Coverage: if code is added or modified in `src/`, corresponding tests in `test/backend_tests/` must be added or updated.
- Two paths: `backend/scripts/test-repl` keeps a warm JVM for the edit-and-rerun loop, and `clojure -M:dev:test` runs kaocha cold (no pnpm wrapper) for CI parity before a commit.

## Warm test JVM (the loop)

- `backend/scripts/test-repl test backend-tests.my-ns-test` runs a namespace; `test backend-tests.my-ns-test/my-test-var` runs one var, and several ids run in turn. No id runs every backend test (`user/run-tests!` selects `^backend-tests.*-test$`).
- Run it in the devenv container from the host: `docker exec -u penpot -w /home/penpot/penpot/backend penpot-devenv-ws0-main scripts/test-repl test backend-tests.my-ns-test` (`penpot-devenv-wsN-main` on wsN). A harness connected to the devenv MCP server has the same through the `clj_test` and `clj_eval` tools.
- Cost: about 0.5 s for a small namespace warm, against about 16 s cold. The first call starts the JVM and loads every namespace (about 20 s).
- The JVM starts with `PENPOT_FLAGS=enable-backend-asserts`, as CI does. Each run reloads the changed namespaces with tools.namespace, then runs `clojure.test` in a fixed (hash) order, not kaocha's random one.
- Exit status: 0 when every test passed, 1 on a failing test, an evaluation error or a timeout, 2 when the JVM cannot start. `PENPOT_TEST_REPL_TIMEOUT` (seconds, default 1800) caps each call.
- `backend/scripts/test-repl eval '(form)'` evaluates in the same JVM; `start`, `stop` and `status` manage it.
- After a `deps.edn` change run `backend/scripts/test-repl stop`: the classpath is fixed at start, so the next call starts a fresh JVM.
- Runs take turns through a lock per workspace, because agents in one workspace share that workspace's test database. A second call waits for the lock; it does not start a second JVM.
- Never call `(restart)` or `(restart-all)` in the test JVM: either would start a whole dev backend inside the test process.
- A def made by hand in the test JVM dies when a reload reloads its namespace.

## Cold path (CI parity, before a commit)

- Before a commit, and to replay a CI order: `scripts/ci backend --test` from the repo root. It runs kaocha (random order) and writes `.ci-logs/backend-test.log`.
- Kaocha prints the seed on failure; `PENPOT_FLAGS=enable-backend-asserts clojure -M:dev:test --seed N` from `backend/` replays that order for the same ids.
- Focus a namespace or a var with `--focus`, and repeat the flag to accumulate ids (kaocha unions them, last-wins does not apply): `PENPOT_FLAGS="enable-backend-asserts" clojure -M:dev:test --focus backend-tests.ns-one --focus backend-tests.ns-two`.
- **Why the flag matters:** `clojure.core/assert` is elided at compile time when `*assert*` is false, and `app.config` only sets that var from the flags when it loads. The test fixture redefines the flags after that, so they never reach it: without the flag the suite runs with every assertion compiled out, and a test that depends on an assert only means something with it set. `scripts/ci` and `tests-backend.yml` export the flag (GitHub Actions sets it too), and `test-repl` starts its JVM with it. A hand-run kaocha, focused or not, needs the flag on the command line.
- If you need to filter output, tee to a temp file first: `PENPOT_FLAGS="enable-backend-asserts" clojure -M:dev:test 2>&1 | tee /tmp/penpot-test-output.txt`.

## Test system and helpers

- RPC test helpers `command!`/`management-command!` split the data map: qualified keys become server params, unqualified keys become request body params. To inject request-level context (headers, `:app.http/auth-key-id`, ip), pass a map under `:app.http/request` metadata; non-map `IRequest` stubs fall back to a dummy request.
- The test system is `main/system-config` + `main/worker-config` with the
  components that must not run in the background dropped: HTTP, the srepl,
  cron, the dispatcher and **every** `[<profile> :app.worker/runner]`
  (`helpers/remove-job-runners`). A live runner of any queue races the tests
  that drive the loop by hand (`wdisp/run-batch` + `@#'wrkr/run-worker-loop`):
  it takes the job the test just dispatched and completes it behind the test's
  back, so the test's own assertions read a state it did not produce. Do not
  re-add a runner by editing that list by hand; the removal matches by key
  shape on purpose.
- `system-config` and `worker-config` are `def`s: their `(cf/get ...)` calls
  are evaluated when the namespace loads, against the real environment, while
  test code calling `cf/get` at run time sees the `penpot-test`-prefixed
  config. The two can disagree — in the devenv `:tenant` is `devenv-ws0` for
  the defs and `default` inside a test — so a component built from
  `worker-config` can talk to a different Redis key prefix than a cfg the test
  builds itself. The symptom is a component that silently does nothing
  locally and races in CI (which sets no `PENPOT_TENANT`).
- `backend-tests.helpers` has `metrics-stub` (a metrics instance with no collectors, where every `run!` is a no-op), `init-climit` (a concurrency limiter built from an inline config map, with an optional second argument for the metrics instance to publish to) and `with-climit`, the macro that builds one, runs the body and halts it. `init-climit` starts the republisher daemon thread, so a test that builds a limiter without halting it leaks that thread for the rest of the JVM run: use `with-climit`. Log lines are capturable with `add-watch` on `app.common.logging/log-record` (it holds the last record; the watcher sees every one, but only of the levels the logger has enabled: a line the app writes at TRACE never gets there).
