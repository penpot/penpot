# Backend metrics

## Recording

- Start at `app.metrics` for collectors and sampling, `app.main/default-metrics` for backend metric definitions, and `app.jobs.metrics` for job metrics. For queue signals see `mem:backend/rpc-concurrency`.
- `app.metrics/run!` catches failures while recording on a collector, not missing-instance errors: validation and collector lookup happen outside that guard. Wire the instance into the component and use `app.metrics/instance` when resolving it from a cfg. Register SQL-backed metrics inside the writing transaction via `db/after-commit`; see `mem:backend/db-transactions`.

## HTTP and process sampling

- A component owns its `app.metrics/create-sampler` scheduler and stops it on halt. A failed sample must not cancel future samples. Start at `app.http/sample-http-metrics`; listener stats depend on Yetti's `:server/statistics` option.
- XNIO's negative samples mean missing data, not zero. Use worker pool size for retained threads: the busy count can stay at zero while threads are blocked in CLIMIT. Undertow exposes absolute totals; publish positive deltas and advance the baseline even on reset so the next sample counts correctly.
- `app.metrics/create-registry` calls `DefaultExports/register`. Keep `jdk.management` in the backend runtime image: without it, reflective OS MXBean lookups can fail silently and `process_*` collectors disappear. See `backend-tests.metrics-test`.
- Body parsing happens before endpoint instrumentation. Count malformed bodies where middleware classifies the failure, using the service's own collector. Do not expect the endpoint counter to include requests rejected before dispatch.

## Log-derived counters

- `app.loggers.metrics/reporter` counts only application `warn`, `error` and `fatal` records that pass through `app.common.logging`; it is not a count of all Loki records. Metric `level` labels are lowercase, unlike Loki's uppercase levels. Keep `logger` values fixed rather than deriving them from request data.
- The watcher runs inside the logging path. Never let a recording failure interrupt logging or log recursively from the watcher itself. A flat counter alone is not proof that no errors occurred.
