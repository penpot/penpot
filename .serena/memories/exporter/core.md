# Exporter Architecture and Workflow

`exporter/`: CLJS/Node **worker process** that consumes the backend's
`:exporter` job queue and renders shapes, frames and files to bitmap, SVG and
PDF. Depends on `common/`; uses Playwright plus export JS/CLJS deps for
SVG/PDF/assets. There is no HTTP surface of its own and nothing is persisted
on this side: the row of the job, its cancel and its result are the backend's.

## Layout and commands

- Source: `exporter/src/`; config: `deps.edn`, `shadow-cljs.edn`, `package.json`; runtime helpers/assets: `vendor/`, `scripts/`.
- From `exporter/`: setup `./scripts/setup`; watch `pnpm run watch` or `pnpm run watch:app`; production build `pnpm run build`; test bundle `pnpm run build:test`; tests `pnpm run test` or `pnpm run test:quiet`; lint `pnpm run lint:clj`; format check/fix `pnpm run check-fmt:clj` / `pnpm run fmt:clj`. `./scripts/ci exporter` in the repo root is green (test-quiet defaults `PENPOT_SECRET_KEY` like `test` does).
- Because exporter consumes `common/`, shared file/shape/model changes may need exporter verification even when the immediate change is not under `exporter/`.
- Cross-cutting testing principles and anti-patterns: `mem:testing`.
- Exporter test conventions and CI: `mem:exporter/testing`.
- Promise chains in the exporter (management client, runner) follow promesa's two API families; arg orders, what each fn must return, the `->>` preference and the async-test silent-green trap: `mem:clojure/promesa` — read before touching promise code.

## The worker anatomy

- The queue wake-up contract (key shape, the two-item payload, mark-then-push, the claim-back rule) is the backend's public surface now: `mem:backend/core`, "The wake-up contract of a worker queue". This process was its first external consumer; read it before writing another.

- Main thread boots pools + K pollers (`core.cljs`): browser pool, wasm pool, temp-file cleaner (`jobs.utils/init`), then `consumer.worker/start!`. Shutdown: browser → wasm pool → pollers → done (the pollers own their connections; the pools unwind while an export is still in flight).
- K pollers (`app.consumer.worker`): one Redis connection per poller (connection = slot + backpressure), BLPOP on `penpot.worker.queue:<tenant>:exporter`, payload `[job-id scheduled-at]` JSON; corrupt payload warns and drops, dead Redis delays 1s and retries. `K = PENPOT_EXPORTER_WORKER_CONCURRENCY` default 2, floor 1; replicas × K is the total concurrency. Pools size themselves to K (`browser` and `wasm.pool` read `ccfg/concurrency`); there is no independent pool-max config anymore, only `:wasm-worker-pool-min`.
- Management client (`app.consumer.api`): claim/progress/create-job-session/complete-job (JSON and multipart via undici FormData)/fail-job, POST to `<internal-uri>/api/management/methods/<method>` with `X-Shared-Key: exporter <key>` (key = `PENPOT_EXPORTER_SHARED_KEY` or HKDF-derived from the secret); transit bodies, non-2xx errors carry `:status` and the error body.
- Runner (`app.consumer.exports`, `run-export!`): mints a render session (`create-job-session`), plans via `make-plan` (single → artifact of its type; multi → zip; frames → pdf via pdfunite), settles via `complete-job-with-artifact` multipart or `fail-job`; the settle never rejects. `app.consumer.plan` holds the render-plan pieces (name transducers, partition of 50, grouping by `[scale type]`).
- Beats: per-object milestones `:preparing/:rendering/:packaging` with `objects`/`pages` counters, throttled at 250ms; a watchdog repeats the last milestone every 1s.
- Cancellation: a `skip` answer on any beat means the row is terminal (cancelled, aborted, settled). The runner raises `:job-cancelled` between units of work; mid-Skia the watchdog fires the local port once (`jobs/mark-cancelled`: flag + terminal local record + SharedArrayBuffer + terminate callbacks registered by `renderer.wasm/with-scope`), killing the leased worker thread. The runner owns the local registry entry via `jobs/register!` and releases it on settle.
- `app.jobs` is the **local cancel arm only**: a runtime registry keyed by job-id (`register!`, `mark-cancelled`, `cancel-signal`, `on-cancel`, `cancelled?`, `release!`). `mark-cancelled` marks the local record terminal BEFORE the callbacks run and returns true/nil (marked-now / no-op for unowned or settled). No redis writes.
- Temp files: `app.jobs.utils/track!`/`release!` per job id; a boot-time clean drops what a previous process left (aged `:exporter-job-ttl`). After the deletion there is no redis-side store, cancel topic or abandoned-job sweep: an unclaimed-in-time row goes `aborted` by the backend's own lease GC.

## Running it (devenv and deploy)

- Devenv: the tmux `exporter` window (`./scripts/watch` + `wait-and-start.sh`) IS the worker now — no separate window, no role env. It needs the backend and valkey of the devenv up; `watch:app` rebuilds with the same shadow build the `main` build uses.
- Deploy: the compose service is one `penpot-exporter` (the same image, `CMD ["node", "app.js"]`); scale by replicas, K is per process. Nginx has no `/api/export` route anymore: the frontend talks to the backend's RPC only (`create-export-assets-job`), and downloads ride `/assets/by-id/?share-id=`/auth of the job resource.
- Useful beats while debugging: the boot log names `:workers K`; each poller logs `running job`/`job settled`; the runner logs `export job settled` with `:outcome`, and a cancel logs `job cancelled by backend` on the watchdog.

## The new tree (refactor in progress)

- New code grows under `exporter.*`, in parallel to the legacy `app.*` tree: `exporter.main` will replace `app.core` once every piece is ported; do not extend `app.*`.
- Lifecycle is `exporter.utils.system`: async-aware Integrant-style init/halt (`init-key`/`halt-key` multimethods, `ref`s, `weavejester.dependency` ordering, `^:async` + `cljs.core/await`, no promesa chains); tests are `^:async deftest` with `await` directly in the body.
- First ported service is `exporter.browser` (whole `app.browser` at once): the running instance is the raw pool, the public API takes it first (`exec` fails fast on a nil pool), the browser factory is injected through config for hermetic tests, and the new tree carries no promesa.
- Async test vars prove their chain only when really awaited: a test passing with 0 assertions means the chain broke upstream.

## Render details

- Headless engines (wasm/Skia) lease one render worker for the whole run (`rd/with-scope`); browser renders go one DOM page per partition of 50.
- Each export gets a fresh Playwright browser context. On success the context closes and the browser returns to the pool; on error the browser is destroyed instead of reused. Borrow validates the connection; pool acquire timeout about 10s; font loading timeout logs a warning and continues after about 15s.
- Bitmap export differs for WASM vs non-WASM render paths: WASM forces Playwright `deviceScaleFactor` to 1 and passes scale through the render URL; non-WASM uses `deviceScaleFactor = scale`.
- WebP is produced by taking a PNG screenshot and converting it with ImageMagick.
- SVG export rasterizes text foreignObjects to PNG, converts through PPM/color masks/potrace, and reassembles SVG paths. It also replaces non-breaking spaces for SVG compatibility and drops empty defs/paths.
- PDF export injects `@page` sizing through raw browser `evaluate` JavaScript; that code cannot rely on CLJS runtime helpers.
- ZIP entry names are sanitized (`plan/sanitize-file-regex`) and duplicates receive numeric suffixes.

## On the render engines (do not lose in refactors)

- The wasm engines run on worker threads; the Skia wasm bundle needs
  `../render-wasm/build export` to generate `src/app/wasm/shared.js` before
  compiling.
- A render thread cannot read a flag: the cancel reaches it through the
  SharedArrayBuffer signal, and a render that never answers is killed by the
  terminate watchdog of `app.wasm.pool` (the same mechanism a hard cancel
  rides on).
- `core.cljs` decides what boots by thread: the main thread consumes the
  queue, a render worker (`wasm.worker/main`) renders.
