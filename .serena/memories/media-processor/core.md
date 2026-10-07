# Media Processor

Stateless HTTP service for Penpot image and font processing. Handles image info extraction, thumbnail generation (sharp), and font conversion (FontForge, woff-tools).

## Tech Stack

- Language: TypeScript
- Runtime: Node.js
- Framework: Express
- Image processing: sharp (libvips)
- Font processing: FontForge (TTF/OTF), sfnt2woff, woff2_decompress
- SVG sanitization: DOMPurify (allowlist) over jsdom (temporary DOM for Node)
- Upload handling: multer (hybrid storage: memory for small, disk for large)
- Logging: pino (with optional Loki transport)
- Config validation: Zod
- Testing: Vitest
- Package Manager: pnpm

## Project Structure

```
media-processor/
├── src/
│   ├── index.ts              # Express app setup, routes, middleware
│   ├── config.ts             # Zod-validated env config, HKDF key derivation
│   ├── types.ts              # TypeScript type definitions
│   ├── upload.ts             # Multer configuration, getFileBuffer helper
│   ├── upload-storage.ts     # Hybrid storage engine (memory < threshold, disk >= threshold)
│   ├── logger.ts             # Pino logger setup
│   ├── svg-pool.ts           # worker_threads pool for SVG sanitization
│   ├── svg-worker.ts         # worker entry point (runs sanitizeSvgSync)
│   ├── middleware/
│   │   ├── auth.ts           # Timing-safe shared key authentication
│   │   ├── error-handler.ts  # ProcessingError class, centralized error handling
│   │   └── timeout.ts        # Request timeout middleware
│   ├── routes/
│   │   ├── health.ts         # GET /api/health
│   │   ├── image.ts          # POST /api/image/info, /api/image/thumbnail
│   │   ├── font.ts           # POST /api/font/convert
│   │   └── svg.ts            # POST /api/svg/sanitize
│   └── services/
│       ├── image.ts          # sharp-based image info/thumbnail generation
│       ├── font.ts           # FontForge/woff-tools font conversion
│       ├── svg.ts            # DOMPurify SVG sanitization over jsdom
│       └── errors.ts         # throwValidation, throwRestriction, throwProcessing
├── test/                     # Vitest test files
├── vitest.config.ts          # Test configuration
├── tsconfig.json             # TypeScript configuration
├── esbuild.config.mjs        # Build configuration
└── package.json              # Dependencies and scripts
```

## Key Conventions

### Auth
- Requests authenticated via `x-shared-key` header using timing-safe comparison
- When no key configured, all requests rejected with 403
- Key derived from `PENPOT_SECRET_KEY` via HKDF (blake2b512) or set directly via `PENPOT_MEDIA_PROCESSOR_SHARED_KEY`

### Resource Limits
- Image: max pixels, max width/height enforced before processing
- SVG: max input size enforced before parsing (default 2MB, `PENPOT_MEDIA_PROCESSOR_SVG_MAX_SIZE`); an oversized input is rejected with 413, never truncated
- SVG workers: `PENPOT_MEDIA_PROCESSOR_SVG_WORKERS` (default 2; 0 runs inline), `PENPOT_MEDIA_PROCESSOR_SVG_WORKER_MAX_OLD_MB` (default 512, per-worker V8 old-generation cap) and `PENPOT_MEDIA_PROCESSOR_SVG_TIMEOUT` (default 30000 ms)
- Font: prlimit wraps FontForge processes with memory (AS) and CPU time limits
- Concurrency: p-queue limits concurrent requests (default 10)
- Upload: hybrid storage — memory for files < 10MB, disk for larger; configurable via `PENPOT_MEDIA_PROCESSOR_MEMORY_THRESHOLD`
- Max file size: configurable (default 350MB)

### Error Handling
- `throwValidation(code, hint)` — 400 errors for invalid input
- `throwRestriction(code, hint)` — 413 errors for resource limits exceeded
- `throwProcessing(code, hint)` — 503 errors for processing failures (e.g., resource limit kills)

### Image Processing
- EXIF orientation applied before dimension validation and thumbnail generation
- sharp caching disabled to prevent unbounded memory growth
- `withoutEnlargement: true` prevents upscaling small images

### Font Conversion
- Supported formats: TTF, OTF, WOFF, WOFF2
- SFNT type detected via magic bytes (0x4f54544f = OTF, 0x00010000 = TTF)
- Temp files cleaned up in finally blocks (best-effort)

### SVG Sanitization
- `POST /api/svg/sanitize` (multipart field `file`) returns the sanitized SVG
  bytes with `Content-Type: image/svg+xml`; it is the remote backend of
  `app.media/sanitize-svg` in the JVM.
- The route never runs the sanitizer on the main thread: `sanitizeSvg` delegates
  to a pool of `worker_threads` workers (`svg-pool.ts` / `svg-worker.ts`), so a
  heavy SVG cannot block the service's event loop (`/api/health` included).
  `sanitizeSvgSync` is the CPU-bound core and stays synchronous. With
  `PENPOT_MEDIA_PROCESSOR_SVG_WORKERS=0` the pool is disabled and the sanitizer
  runs inline (tests and an escape hatch).
- The pool is created at boot, queues when every worker is busy, and applies
  `resourceLimits: { maxOldGenerationSizeMb }` per worker: a worker that runs out
  of memory dies with an error instead of the kernel killing the process, and the
  pool maps that to a 503 and respawns it. A stuck job is terminated by a timeout
  and its worker respawned. Worst-case memory is roughly workers ×
  maxOldGenerationSizeMb.
- DOMPurify is given `USE_PROFILES: {svg: true, svgFilters: true}` and
  `NAMESPACE: "http://www.w3.org/2000/svg"`, so it parses the document as XML/SVG
  the way a browser parses a standalone `.svg`. Its default allowlist fails closed
  and already drops `script`, `foreignObject`, `set`, `animate` and `use`; do not
  add `ADD_TAGS`/`ADD_ATTR`/`FORBID_TAGS`, and do not use `IN_PLACE`, `setConfig`
  or hooks. To fix a bypass, upgrade DOMPurify (it is a fast-moving security
  dependency; pin the latest patched 3.x).
- A fresh jsdom `window` is created per call and closed in `finally`. Reusing one
  window in a long-lived process leaks memory and degrades latency without bound.
- The XML declaration and DOCTYPE are stripped before parsing (the XML parser
  bails on them; stripping the DOCTYPE also keeps its entities undeclared). An
  input that does not produce an `<svg` root is rejected with 400
  `invalid-svg-file`.
- Error mapping: a parse/validation failure is 400 `invalid-svg-file`; anything
  else (a bug, an OOM, a DOMPurify regression) is logged and reported as 503
  `svg-sanitization-failed`, never as an invalid file.
- The 2MB cap is deliberately lower than the backend's `:media-max-file-size`
  (30MiB): the jsdom parse cost and memory do not scale with bytes the way
  sharp's does, so a large SVG is a denial-of-service vector. The backend enforces
  the same cap (`:media-svg-max-file-size`, `mem:backend/media-sanitization`) so
  both modes behave the same; keep the two knobs aligned.
- Errors: `400 invalid-svg-file` (not a well-formed SVG), `413 svg-too-large`,
  `503 svg-sanitization-failed` / `svg-timeout` / `svg-worker-failed`.
- Tests: `svg.test.ts` (service corpus, route, and the exact multipart the JVM
  backend builds) and `svg-pool.test.ts` (queue, timeout, failure/respawn with a
  fake worker). The contract test pins the wire format but does not run the JVM
  against a live service — that gap is known and accepted.

## Commands

All commands run from `media-processor/` directory:

- `pnpm run test` — Run Vitest test suite
- `pnpm run types:check` — TypeScript type checking (tsc --noEmit)
- `pnpm run fmt` — Format code with Prettier
- `pnpm run fmt:check` — Check formatting without modifying
- `pnpm run build` — Build for production (esbuild)
- `pnpm run start:dev` — Start development server (tsx)

## Docker

- Exposed port: 6065 (configurable via `PENPOT_MEDIA_PROCESSOR_PORT`)
- Must be deployed on internal Docker network only (not public-facing)
- Backend communicates via `PENPOT_MEDIA_PROCESSING_SERVICE_URI`

## Testing Principles

Cross-cutting testing principles and anti-patterns: `mem:testing`.

- Run `pnpm run test` after changes
- Run `pnpm run types:check` after TypeScript changes
- Run `pnpm run fmt:check` before commits
