# Media Processor

Media worker of the Penpot jobs substrate. It runs the durable media jobs
(image info and thumbnails with sharp, font conversion with FontForge and
woff-tools) and uploads every result to the `tempfile` bucket. It is a
long-lived process, not an HTTP service: it consumes two Redis queues and
settles each job in the Postgres-backed ledger.

SVG sanitization never reaches this worker: it is always local in the backend.

## Runtime

- TypeScript on Node.js; no Express, multer or p-queue.
- `src/protocol.ts` — queue key, job payload and per-job Zod schemas.
- `src/config.ts` — Zod-validated environment.
- `src/backend-client.ts` — input download and result upload.
- `src/jobs-client.ts` — ledger calls (`claim`, `progress`, `complete`,
  `fail`) over the management API.
- `src/jobs-worker.ts` — the fast and slow groups of BLPOP pollers.
- `src/jobs/media.ts` — the handlers of the four media jobs.
- `src/index.ts` — config, logger, health server, worker and signals.
- `src/health.ts` — `GET`/`HEAD /health` for the deployment probe.
- `src/services/` — image (sharp) and font (FontForge) processing.
- `src/svg-pool.ts` / `src/svg-worker.ts` — kept for the SVG path, but no
  media job routes to them.

## Job contract

`app.media.jobs/queue-pair` routes the same job definitions to the local
runners (`:media-local-fast` and `:media-local`) or to this worker
(`:media-fast` and `:media`) depending on the `:remote-media-processing`
flag. The flag changes the executor and the queues, never the contract.

- Queue: `penpot.worker.queue:<tenant>:<queue>`.
- Payload: `[job-id, scheduled-at]` as JSON.
- Each payload is claimed with `claim-job`; the answer names the job and its
  params, or `skip` when the row is not runnable (cancelled, already taken,
  terminal).
- The job is settled with `complete-job` or `fail-job`. A domain error is
  terminal; a crash is reported as retriable and the substrate decides
  whether it gets another attempt.
- `media-info` and `profile-thumbnail` go to the fast queue. The
  `media-thumbnail` fan-out and `font-variants` go to the slow one, so a
  long fan-out cannot delay an upload.
- Params always carry the input as `{id, mtype, size}`, a `tempfile`
  object; never a local path.
- Results are uploaded to `tempfile` and answered as `{id, uri, mtype, size}`
  inside `{thumbnail}` or `{photo}`, or as a `{variants}` list. The backend's
  terminal hook moves them to the final bucket and releases the input.

## Shutdown

`SIGTERM`/`SIGINT`: stop popping, abort the jobs in flight, wait up to 30 s
for the pollers, report the ones whose handler ignored the abort as
`internal/worker-shutdown` with `retriable: true` (so the substrate schedules
another attempt instead of waiting for the lease), then close the
connections, the SVG pool and the health server. A result produced after
that report is dropped: the row already belongs to another attempt.

## Cancellation

Every claimed job has its own `AbortController`. The signal is aborted when
`report-job-progress` answers `skip` (the ledger says the row is no longer
active) and when the worker stops. The handlers forward it to
`getImageInfo`, `generateThumbnail` and `convertFont`; the font helpers pass
it to `execFile`, so an in-flight FontForge, `sfnt2woff` or
`woff2_decompress` process dies with it. Sharp cannot abort a running
`toBuffer()`, so the image path checks the signal before and after the
pipeline.

An aborted handler never uploads. After a `cancelled` abort it does not call
`complete-job` or `fail-job` either: the backend already settled the row. On
shutdown the worker reports the row itself as `internal/worker-shutdown` with
`retriable: true`.

## Verifying both executors

`:remote-media-processing` chooses the executor; the jobs are the same, so
both modes must answer the same contract. To check one end to end:

1. **Local** (flag off, the devenv default: `backend/scripts/_env` has
   `disable-remote-media-processing`). Leave this worker stopped and keep the
   backend with `enable-backend-worker` (on by default on ws0). Upload a large
   PNG, a TTF font and a profile photo. `select name, status from job;` shows
   `media-info`, `media-thumbnail`, `font-variants` and `profile-thumbnail`
   as `completed`. Upload an SVG too: it renders, and no `media-info` row
   appears, because `media/info` parses it inline.
2. **Remote**. Set `enable-remote-media-processing` in `PENPOT_FLAGS`, restart
   the backend and run `pnpm run start:dev` here. Repeat the uploads: the
   worker log shows `job completed`, the rows end `completed`, and the
   dashboard paints the font as processing until its job ends.
3. **Cancellation**. Start a font upload and delete the font (or the variant)
   before the job ends: the backend cancels it by label, the worker aborts the
   FontForge child process and never uploads a result.
4. **Shutdown**. Send `SIGTERM` to the worker during a font job: the row ends
   as `internal/worker-shutdown` with `retriable: true`, and the substrate
   schedules another attempt.

`job` is the source of truth in both modes: a mode that leaves rows `running`
until their lease, or a result in `tempfile` nobody moved, is a failed run.

## Data plane

The backend puts the input in the `tempfile` bucket (no owner, 10-minute
`touched-at`). The worker downloads it with
`X-Shared-Key: media-processor <key>` from `/assets/by-id/:id`; the backend only
accepts that key for `tempfile` objects. Results go to `upload-tempfile` with
the same key; the backend resolves them directly from storage.

With the `fs` storage backend the backend answers `/assets/by-id` with `204` +
`x-accel-redirect`, so `PENPOT_MEDIA_PROCESSOR_BACKEND_URI` must point at the
public/nginx entrypoint, not at the backend directly. With S3 it gets a
presigned URL per object and either URI works.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PENPOT_MEDIA_PROCESSOR_REDIS_URI` | `redis://redis/0` | Queue keys |
| `PENPOT_MEDIA_PROCESSOR_TENANT` | `default` | Queue prefix and claim tenant |
| `PENPOT_MEDIA_PROCESSOR_JOBS_FAST_QUEUE` | `media-fast` | Queue of the synchronous jobs |
| `PENPOT_MEDIA_PROCESSOR_JOBS_QUEUE` | `media` | Queue of the asynchronous jobs |
| `PENPOT_MEDIA_PROCESSOR_JOBS_FAST_CONCURRENCY` | `2` | Pollers on the fast queue |
| `PENPOT_MEDIA_PROCESSOR_JOBS_CONCURRENCY` | `2` | Pollers on the slow queue |
| `PENPOT_MEDIA_PROCESSOR_BACKEND_URI` | — (required) | Ledger, input and upload backend |
| `PENPOT_MEDIA_PROCESSOR_BACKEND_TIMEOUT` | 60 s | Backend client and ledger timeout |
| `PENPOT_MEDIA_PROCESSOR_SHARED_KEY` | — | Media key; falls back to HKDF over `PENPOT_SECRET_KEY` |
| `PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE` | 350 MiB | Input download cap; the backend sends at most 30 MiB (`:media-max-file-size`) |
| `PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_PIXELS` | 128000000 | sharp limit |
| `PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_WIDTH` / `_HEIGHT` | `16384` | sharp limit |
| `PENPOT_MEDIA_PROCESSOR_SVG_MAX_SIZE` | 2 MiB | SVG cap (main thread and worker) |
| `PENPOT_MEDIA_PROCESSOR_SVG_WORKERS` | `2` | Pool size; `0` runs inline and blocks the event loop |
| `PENPOT_MEDIA_PROCESSOR_SVG_WORKER_MAX_OLD_MB` | `512` | Worker heap cap |
| `PENPOT_MEDIA_PROCESSOR_SVG_TIMEOUT` | `30000` | Pool job timeout (ms) |
| `PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM` | `512` | FontForge address space (MiB) |
| `PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME` | `30` | FontForge CPU seconds |
| `PENPOT_MEDIA_PROCESSOR_FONT_TIMEOUT` | `120000` | FontForge timeout (ms) |
| `PENPOT_MEDIA_PROCESSOR_PORT` / `_HOST` | `6065` / `0.0.0.0` | Health server only |

The process also reads the usual `PENPOT_LOGGERS_LOKI_*` variables. The queue
names must match the backend's `app.media.jobs/queue-pair`; the worker does not
discover them. Deploy the backend with the `media-processor` shared key and the
management allowlist before the worker: without it `claim-job` answers 403 and
the jobs stay for the lease.

## Security and operations

The worker parses hostile files; it is the least-trusted component. It has the
media shared key and Redis access, but no database, S3 credentials or master
secret (when deployed with the derived key). The only HTTP surface is
`/health`, a liveness probe (200 even when Redis is down); run the worker on an
internal network and expose no other port.

## Commands

From `media-processor/`:

- `pnpm run test` — Vitest suite.
- `pnpm run types:check` — `tsc --noEmit`.
- `pnpm run fmt:check` / `pnpm run fmt` — Prettier.
- `pnpm run build` — esbuild bundle (`dist/index.js` and `dist/svg-worker.js`).
- `pnpm run start` — run the bundle.
- `pnpm run start:dev` — `tsx` with the backend env file. It is only needed
  with `:remote-media-processing` on: with the flag off the backend runners
  consume the local queues in the backend process.

The poller tests use the in-memory Redis double in `test/fake-redis.ts`;
`test/svg-worker-real.test.ts` exercises the real worker thread. There is no
media-processor service in the devenv compose: run it manually with
`pnpm run start:dev` when you need the remote executor.
