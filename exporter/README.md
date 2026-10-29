# Exporter

Node process that renders shapes, frames and files to bitmap, SVG and PDF. It
is a **consumer**: it polls the backend's `:exporter` job queue on Redis and
drives each export through the backend's management API — claim, report the
milestones, and settle (`complete-job` multipart with the artifact, or
`fail-job` on error). There is no HTTP surface of its own and nothing is
persisted on this side: the row of the job, its cancel button and its result
all live in the backend.

## How a job runs

1. The backend dispatcher pushes `[job-id, scheduled-at]` (JSON) into
   `penpot.worker.queue:<tenant>:exporter`.
2. One of the `K` pollers pops it and owns that connection for the whole run:
   the blocking pop is both the semaphore of the slot and the backpressure, so
   the process never runs more exports than pollers. `K` is
   `PENPOT_EXPORTER_WORKER_CONCURRENCY` (default 2, and the browser and wasm
   pools size themselves to the same figure).
3. The poller claims the job over the management API and, on `:run`, mints a
   render session (`create-job-session`) through which every asset fetch
   happens as the owner of the job.
4. The milestones are reported as beats (`:preparing`, `:rendering`,
   `:packaging`, with the `objects` and `pages` counters). A watchdog re-sends
   the last one every second, whatever the run is doing.
5. It settles with `complete-job` (the artifact rides multipart, the session
   is closed in the same step) or `fail-job`. The settle never rejects: a
   worker that cannot talk leaves the row to the backend's lease GC.

## Cancellation

A cancellation reaches the worker through the beats: a `skip` answer means the
backend no longer listens (the row is terminal), so the runner stops between
units of work; mid-Skia the watchdog terminates the leased render worker. The
row was already `cancelled` by the backend's `cancel-job`.

## Configuration

| Variable                                 | Default | Description                                        |
|------------------------------------------|---------|----------------------------------------------------|
| `PENPOT_PUBLIC_URI`                      | —       | The frontend the assets render through             |
| `PENPOT_INTERNAL_URI`                    | `public-uri` | Same network's frontend; the management API too |
| `PENPOT_REDIS_URI`                       | `redis://redis/0` | The queue and the rest of redis           |
| `PENPOT_TENANT`                          | `default` | Queue prefix (workspace name in devenv)          |
| `PENPOT_EXPORTER_SHARED_KEY`             | —       | The shared key; derived from the secret otherwise   |
| `PENPOT_EXPORTER_JOB_TTL`                | `3600`  | Lifetime of the temp files a job owns, in seconds  |
| `PENPOT_EXPORTER_WORKER_CONCURRENCY`     | `2`     | `K`: pollers, running exports, pool sizes          |
| `PENPOT_WASM_WORKER_POOL_MIN`            | `1`     | Render workers kept warm; clamped to the max       |
| `PENPOT_WASM_WORKER_IDLE_TIMEOUT`        | `300`   | Silence before a worker is terminated, in seconds  |
| `PENPOT_WASM_WORKER_IMAGE_CACHE_SIZE`    | `134217728` | Per-worker image cache budget, in bytes        |

## Inspecting the backend

Nothing about a job is stored here: `get-job` in the backend answers the full
row, and its `app.debug`/admin surfaces render the same records.
