# Exporter

Node service that renders shapes and files to bitmap, SVG and PDF. Wasm exports
are **jobs**: created over HTTP, admitted by a scheduler with bounded
concurrency, and persisted in Redis so their state can be queried and cancelled.
The legacy entry point, which is what the browser backend still goes through,
runs the export as soon as it is asked for, with no admission control.

## HTTP API

Mounted under `/api/export` (the router matches on the path *after* that prefix,
so it also works when the process is hit directly on `/`).

| Method   | Path            | Description                                        |
|----------|-----------------|----------------------------------------------------|
| `POST`   | `/`             | Legacy command multiplex; runs unscheduled         |
| `POST`   | `/jobs`         | Create an export job                               |
| `GET`    | `/jobs/{id}`    | Job record                                         |
| `DELETE` | `/jobs/{id}`    | Request cancellation                               |

Job states: `queued` -> `running` -> `ended` | `error` | `cancelled`. The last
three are terminal.

## Redis layout

Every key is namespaced with `penpot.exporter.` plus the tenant
(`PENPOT_TENANT`, `default` in code but set to the workspace name in devenv,
e.g. `devenv-ws0`).

```
penpot.exporter.{tenant}.job.{job-id}  hash    field: data (transit blob of the
                                               whole record)
penpot.exporter.{tenant}.job-cancel    pubsub  payload: the job id, one line
```

There is no index: the keyspace is one self-expiring hash per job and nothing
else. Each hash carries the same TTL as the exported file
(`PENPOT_EXPORTER_JOB_TTL`, default 3600s), refreshed on every write and never
after the job settles.

## Inspecting Redis

Redis is not published on the host, so `redis-cli` from your machine gets
connection refused. Run it **inside the devenv container**, against the `valkey`
host on database 0:

```bash
redis-cli -h valkey -n 0
```

`redis-cli -u "$PENPOT_REDIS_URI"` does the same and follows whatever the env is
set to (`redis://valkey/0` in devenv).

Keys carry the tenant, which in devenv is the **workspace name**
(`$PENPOT_TENANT`, e.g. `devenv-ws0`), not `default`. From the prompt:

```
# every job record
KEYS penpot.exporter.devenv-ws0.job.*

# the whole record, transit-json in the `data` field
HGET penpot.exporter.devenv-ws0.job.<job-id> data

# seconds left before the record expires
TTL penpot.exporter.devenv-ws0.job.<job-id>

# watch cancellations as they are published (blocks the connection)
SUBSCRIBE penpot.exporter.devenv-ws0.job-cancel

# drop one record
DEL penpot.exporter.devenv-ws0.job.<job-id>
```

`KEYS` is fine here -- the keyspace is a handful of job hashes. On a real
deployment use `SCAN 0 MATCH penpot.exporter.<tenant>.job.* COUNT 100` instead.
Do not `FLUSHDB`: the backend shares this database.

The backend debug UI also renders these records: `/dbg` has an *Export jobs*
section, with a `?job-id=` filter.

## Configuration

| Variable                              | Default | Description                          |
|---------------------------------------|---------|--------------------------------------|
| `PENPOT_REDIS_URI`                    | `redis://redis/0` | Job store and cancel topic |
| `PENPOT_TENANT`                       | `default` | Key and topic prefix               |
| `PENPOT_EXPORTER_JOB_TTL`             | `3600`  | Lifetime of a job record, in seconds  |
| `PENPOT_EXPORTER_MAX_CONCURRENT_JOBS` | `4`     | Admission limit                       |
| `PENPOT_EXPORTER_MAX_JOBS_PER_PROFILE`| `2`     | Per-profile admission limit           |
| `PENPOT_EXPORTER_QUEUE_MAX`           | `64`    | Queue cap; over it, `429 :queue-full` |
| `PENPOT_WASM_WORKER_POOL_MAX`         | `2`     | Headless render worker threads; min 1 |
| `PENPOT_WASM_WORKER_POOL_MIN`         | `1`     | Workers kept warm; clamped to the max |
| `PENPOT_WASM_WORKER_IDLE_TIMEOUT`     | `300`   | Silence before a worker is terminated, in seconds |
| `PENPOT_WASM_WORKER_IMAGE_CACHE_SIZE` | `134217728` | Per-worker image cache budget, in bytes |

A headless job leases one render worker for its whole run, so it is admitted
only when a worker is free: `PENPOT_WASM_WORKER_POOL_MAX` is the real limit for
them, and `PENPOT_EXPORTER_MAX_CONCURRENT_JOBS` bounds the browser ones
alongside.
