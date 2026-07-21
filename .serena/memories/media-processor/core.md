# Media Processor

Stateless HTTP service for Penpot image and font processing. Handles image info extraction, thumbnail generation (sharp), and font conversion (FontForge, woff-tools).

## Tech Stack

- Language: TypeScript
- Runtime: Node.js
- Framework: Express
- Image processing: sharp (libvips)
- Font processing: FontForge (TTF/OTF), sfnt2woff, woff2_decompress
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
│   ├── middleware/
│   │   ├── auth.ts           # Timing-safe shared key authentication
│   │   ├── error-handler.ts  # ProcessingError class, centralized error handling
│   │   └── timeout.ts        # Request timeout middleware
│   ├── routes/
│   │   ├── health.ts         # GET /api/health
│   │   ├── image.ts          # POST /api/image/info, /api/image/thumbnail
│   │   └── font.ts           # POST /api/font/convert
│   └── services/
│       ├── image.ts          # sharp-based image info/thumbnail generation
│       ├── font.ts           # FontForge/woff-tools font conversion
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
