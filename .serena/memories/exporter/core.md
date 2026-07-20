# Exporter Architecture and Workflow

`exporter/`: CLJS/Node headless export service. Depends on `common/`; uses Playwright plus export JS/CLJS deps for SVG/PDF/assets.

## Layout and commands

- Source: `exporter/src/`; config: `deps.edn`, `shadow-cljs.edn`, `package.json`; runtime helpers/assets: `vendor/`, `scripts/`.
- From `exporter/`: setup `./scripts/setup`; watch `pnpm run watch` or `pnpm run watch:app`; production build `pnpm run build`; lint `pnpm run lint`; format check/fix `pnpm run check-fmt` / `pnpm run fmt`.
- Because exporter consumes `common/`, shared file/shape/model changes may need exporter verification even when the immediate change is not under `exporter/`.
- Cross-cutting testing principles and anti-patterns: `mem:testing`.

## HTTP and browser pool

- POST body limit is about 60 MB. Exporter supports `application/transit+json`; request params merge query params and body params.
- Map response bodies are Transit JSON and force HTTP 200; nil 200 bodies become 204.
- Auth token comes from cookie `auth-token`, then uploads use Bearer auth plus the management shared key.
- Each export job gets a fresh Playwright browser context. On success, the context closes and the browser returns to the pool; on error, the browser is destroyed instead of reused.
- Borrow validates browser connection. Pool acquire timeout is about 10s; font loading timeout logs a warning and continues after about 15s.

## Export batching and async behavior

- `prepare-exports` groups entries by `[scale type]` and partitions groups into chunks of 50. Each partition uses file/page/share/name from its first item, so be careful if entries might cross those boundaries.
- Single-export response is used only when multiple export is not forced and there is exactly one prepared export containing exactly one object.
- Multi-object export can run async: when `wait` is false it returns a resource immediately and publishes progress/end/error to Redis by profile topic; when `wait` is true it waits for upload and returns the uploaded resource.
- Frame export returns a resource immediately and publishes Redis updates; it does not follow the same `wait` option path.
- ZIP entry names are sanitized and duplicates receive numeric suffixes.

## Render details

- Bitmap export differs for WASM vs non-WASM render paths: WASM forces Playwright `deviceScaleFactor` to 1 and passes scale through the render URL; non-WASM uses `deviceScaleFactor = scale`.
- WebP is produced by taking a PNG screenshot and converting it with ImageMagick.
- SVG export rasterizes text foreignObjects to PNG, converts through PPM/color masks/potrace, and reassembles SVG paths. It also replaces non-breaking spaces for SVG compatibility and drops empty defs/paths.
- PDF export injects `@page` sizing through raw browser `evaluate` JavaScript; that code cannot rely on CLJS runtime helpers.
- Temporary resources schedule local deletion, then uploads POST to `/api/management/methods/upload-tempfile` with `X-Shared-Key: exporter <management-key>` and Bearer auth.