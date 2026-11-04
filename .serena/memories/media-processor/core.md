# Media Processor

Media worker of the unified jobs substrate. It runs the durable media jobs (image info and thumbnails with sharp/libvips, font conversion with FontForge, sfnt2woff and woff2_decompress) and uploads every result to the `tempfile` bucket. It is a long-lived TypeScript process, not an HTTP service. SVG sanitization never reaches it: that is always local in the backend.

**Wired.** With `:remote-media-processing` the backend routes the media jobs to `:media-fast`/`:media` and this worker consumes them; without the flag they go to `:media-local-fast`/`:media-local` and the backend runners do the work in the backend process, so the worker is not needed at all. `src/jobs-worker.ts` runs the pollers, `src/jobs-client.ts` settles the ledger rows and `src/jobs/media.ts` has the four handlers. There is no request/reply path anymore: the ephemeral `src/worker.ts` and `src/commands.ts` are gone, and `app.jobs/request` is substrate API without a media producer.

## Runtime and layout

- TypeScript on Node.js. No Express, multer or p-queue.
- `src/protocol.ts` — queue key, `[job-id, scheduled-at]` payload and the per-job Zod schemas.
- `src/config.ts` — Zod-validated environment; `loadLogConfig` is a separate subset so importing a service never requires the worker settings.
- `src/backend-client.ts` — `fetchInput` (streaming download with cap) and `uploadResult` (multipart to `upload-tempfile`).
- `src/jobs-client.ts` — `claim`, `progress`, `complete` and `fail` against `/api/management/methods/*` with the media shared key. An HTTP error throws (never an action), so the poller keeps the row for its lease.
- `src/jobs-worker.ts` — two groups of BLPOP pollers, one connection each; a job is claimed, dispatched by name and settled with `complete`/`fail`.
- `src/jobs/media.ts` — handlers of `media-info`, `media-thumbnail`, `profile-thumbnail` and `font-variants`.
- `src/index.ts` — config, logger, health server, worker start and SIGTERM/SIGINT.
- `src/health.ts` — `GET`/`HEAD /health`, matched by pathname (query string tolerated).
- `src/services/image.ts` and `font.ts`; `src/http.ts` holds the URL and error helpers shared by the two HTTP clients.

## Job contract

- Queue key: `penpot.worker.queue:<tenant>:<queue>`, with `<queue>` from `PENPOT_MEDIA_PROCESSOR_JOBS_FAST_QUEUE` (default `media-fast`) and `PENPOT_MEDIA_PROCESSOR_JOBS_QUEUE` (default `media`). They must match `app.media.jobs/queue-pair`.
- Payload: `[job-id, scheduled-at]` as JSON, the same tuples the backend dispatcher and the immediate push of `jobs/submit` produce.
- Claim answers `{action: run, name, params}` or `{action: skip}`. `skip` means the row is not runnable (cancelled, taken by another attempt, terminal): nothing runs and nothing is written.
- A job name without a handler is failed with `internal/no-job-handler` and `retriable: false`.
- A `ProcessingError` is terminal and keeps its `errorBody`; any other throw is `internal/processing-error` with a generic hint and `retriable: true`.
- Params always carry `input = {id, mtype, size}` (`tempfile` object); never a local path. Results are uploaded to `tempfile` and answered as `{id, uri, mtype, size}` inside `{thumbnail}`, `{photo}` or `{variants}`; the backend's terminal hook moves them and releases the input.
- `font-variants` reports progress per target (`stage: "fonts"`, `counters.fonts`) and stops the fan-out when progress answers `skip`.
- The worker never publishes on a reply key; `submit-and-wait` notification lives in the backend's terminal transaction.

## Pollers and shutdown

- Fast group consumes the synchronous jobs (`media-info`, `profile-thumbnail`); slow group consumes `media-thumbnail` and `font-variants`, so a fan-out cannot delay an upload. Concurrency is `_JOBS_FAST_CONCURRENCY` (default 2) and `_JOBS_CONCURRENCY` (default 2); each poller owns its Redis connection and awaits its handler before popping again, so the connections are the concurrency ceiling and the backpressure.
- `BLPOP` timeout 5 s. A corrupt payload is logged and dropped. A Redis failure is a lost turn: the poller waits 1 s and retries.
- A failed claim is logged and the row is left alone (the dispatcher reschedules it or the lease aborts it); it is never treated as a `skip`.
- `stop` stops popping, aborts every job in flight (each has its own `AbortController`), waits up to 30 s for the pollers, and reports the handlers that ignored the abort as `internal/worker-shutdown` with `retriable: true`, so the substrate schedules another attempt instead of waiting for the lease. A result produced after that report is dropped: the row already belongs to another attempt. Then it quits the connections. Idempotent. A claim that lands after `stop` began is aborted before its handler runs.
- `SIGTERM`/`SIGINT` call `stop` before closing the health server and the logger.

## Cancellation

- Every claimed job has its own `AbortController`. `JobContext` carries `signal` and `abort(reason)`, with two reasons: `cancelled` (the ledger says the row is no longer active) and `shutdown` (the worker is stopping).
- `report-job-progress` answering `skip` calls `context.abort("cancelled")` and stops the fan-out. The worker drops the outcome: the row is already terminal, so it never uploads and never calls `complete`/`fail`.
- `stop` aborts the jobs in flight before waiting, so an in-flight font child process dies at once and the poller reports the row as `worker-shutdown` retriable. A handler that ignores the signal is reported after the 30 s cap. `abandoned` makes the shutdown report idempotent.
- `getImageInfo`, `generateThumbnail`, `convertFont` and every `font.ts` helper take an optional `AbortSignal`; `execCommand` forwards it to `execFile`, which kills the child process (FontForge, `sfnt2woff`, `woff2_decompress`). Sharp/libvips cannot abort a running pipeline (`toBuffer()` takes no signal), so the image checks are cancellation checkpoints before and after it.

## Data plane

- The backend uploads the input to the `tempfile` bucket: no owner, `touched-at` +10 min, no dedup.
- The worker downloads it with `X-Shared-Key: media-processor <key>` from `/assets/by-id/:id`. The backend accepts that key only for `tempfile` objects.
- The worker uploads results to `upload-tempfile` with the same key; the backend resolves the result object directly from storage.
- With the `fs` storage backend the backend answers `/assets/by-id` with `204` + `x-accel-redirect`, so the worker's `BACKEND_URI` must point at the public/nginx entrypoint, not at the backend directly. With S3 it gets a presigned URL per object and either URI works.
- The worker never holds S3 credentials. Tempfile inputs are ownerless; sessions cannot read them (`owner-match?` fails closed), only the media shared key can.

## Configuration

Defaults and env names:

- `PENPOT_MEDIA_PROCESSOR_REDIS_URI` `redis://redis/0`; `_TENANT` `default`; `_BACKEND_URI` required; `_BACKEND_TIMEOUT` 60 s (also the ledger timeout).
- `_JOBS_FAST_QUEUE` `media-fast`; `_JOBS_QUEUE` `media`; `_JOBS_FAST_CONCURRENCY` 2; `_JOBS_CONCURRENCY` 2. An empty queue name is rejected at boot.
- `_SHARED_KEY` optional; if absent, HKDF `blake2b512` over `PENPOT_SECRET_KEY` with salt `media-processor` (base64url). Prefer passing the derived key directly, not the master secret. A missing URI or key aborts the boot.
- `_MAX_FILE_SIZE` 350 MiB (the backend sends at most 30 MiB, `:media-max-file-size`).
- Image limits: `_IMAGE_MAX_PIXELS` 128000000, `_IMAGE_MAX_WIDTH`/`_HEIGHT` 16384. They are applied at boot by `runtime.ts`; a missing call is a regression.
- Font: `_FONT_PROCESS_MEM` 512, `_FONT_PROCESS_CPU_TIME` 30, `_FONT_TIMEOUT` 120000.
- `_PORT` 6065 / `_HOST` 0.0.0.0 are only for the health server.
- The usual `PENPOT_LOGGERS_LOKI_*` variables feed pino.

## Commands

From `media-processor/`:

- `pnpm run test`, `pnpm run types:check`, `pnpm run fmt:check`, `pnpm run build`.
- `pnpm run start` (bundle) and `pnpm run start:dev` (`tsx` + backend env file; only needed with the remote flag on).
- Build emits `dist/index.js`; the only external runtime deps are `sharp`, `ioredis`, `pino`, `pino-pretty` and `pino-loki`.

The poller tests use the in-memory Redis double in `test/fake-redis.ts` (real BLPOP semantics, no external Redis). There is no media-processor service in the devenv compose: run it with `start:dev` and the env vars. The README's "Verifying both executors" is the manual runbook for local vs remote.

## Security and operations

- The worker parses hostile files; it is the least-trusted component. It has the media shared key and Redis access, but no database, S3 credentials or master secret (when deployed with the derived key).
- The media key is scoped to `tempfile` reads in `/assets/by-id` and to the management methods that declare it (`claim-job`, `report-job-progress`, `complete-job`, `fail-job`, `upload-tempfile`).
- The only HTTP surface is `/health`; keep the worker on an internal network and expose no other port.
- `/health` is liveness, not readiness: it answers 200 even when Redis is down.
