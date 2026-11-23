# Backend Subtleties

## RPC exposure and wrappers

- Command registration, validation and wrapper order: `mem:backend/http-rpc`.

## Concurrency limiter (CLIMIT)

- Limit declarations, acquisition order and queue diagnosis: `mem:backend/rpc-concurrency`.

## DB helpers

- Transaction ownership, callbacks and advisory locks: `mem:backend/db-transactions`.

## Workers and cron

- Job lifecycle, recovery and cron overlap: `mem:backend/jobs`.
- Startup failures in injected component schemas: `mem:backend/integrant-schemas`.

## Config and HTTP/session middleware

- Environment values and PostgreSQL connection settings: `mem:backend/config`.
- Request parsing, response negotiation and session auth: `mem:backend/http-rpc`.

## HTTP server self-metrics

- HTTP listener and worker sampling, missing samples and JVM runtime requirements: `mem:backend/metrics`.

## Metrics recording

- Collector failures, metrics wiring and transaction boundaries: `mem:backend/metrics`.

## Log-derived metrics

- What the reporter counts and how its labels differ from Loki: `mem:backend/metrics`.

## Storage and media

- The `media-storage-bytes-per-team` quota check on `upload-file-media-object` is TEMPORARILY DISABLED (2026-10-19, kept commented in `app.rpc.commands.media` with its `sql:get-team-id-for-file` lookup still active for the audit props): the quote performs a full `SUM` over `storage_object` per upload and was too slow in production. The `upload-sessions-per-profile` quota is unaffected and stays active. Re-enable only with a cheaper approximation.
- `file-gc` trims emit `file-gc` with the cleaned `:file-id` and its `:team-id`; `delete-object-with-cascade` carries flat `:team-ids` next to the nested `:cascade` map. A consumer can key on those props without joining production tables.
- Storage abstraction, backend configuration, logical buckets, object lifecycle, deduplication, access rules, and garbage collection: `mem:backend/storage`.
- SVG validation strips DOCTYPE and uses secure SAX parsing. Basic SVG info falls back to 100x100 dimensions when width/height/viewBox are missing.
- The SVG sanitizer (`app.media.svg`) is a **blocklist**, not an allowlist: it drops dangerous elements and attributes by name and escapes every text and attribute value when it writes the tree back, so a payload hidden in a value cannot become live markup again. The allowlist rework is [#12190](https://github.com/penpot/penpot/issues/12190) and lands in media-processor, not here. `sanitize-svg` also tracks the namespace bindings through the recursion and drops any element that is not in the SVG namespace, so a document whose root is not an `svg` element in the SVG namespace is rejected and a child that rebinds `xmlns` to XHTML (`iframe`/`srcdoc`/`meta`) is dropped with its subtree; it compares the scheme of an URL the way the browser does (dropping tab/CR/LF/spaces), so `java&#x09;script:` does not slip through the `javascript:` filter. The paths that store an uploaded SVG as its final object go through `app.media.svg/sanitize-svg-upload` (media upload, the URL download, both thumbnail writers and the organization logo); the three binfile imports go through `app.binfile.common/sanitize-imported-svg`, which calls `sanitize-svg` directly. The profile picture is not an SVG: `import-profile-picture` ignores an `image/svg+xml` download, because the avatar is always rendered as an image. The caller checks the size before calling it: the helper reads the whole file, and the size limit applies to the upload, not to the sanitized copy, which can be up to six times bigger.
- Upload handlers run the `media.v` checks in a fixed order: `check-upload-path`, then `check-media-type`, then `check-media-size` (or `check-font-size` for fonts). The path check gates every later read: `check-media-type` sniffs the bytes at `:path` and `check-media-size` reads `fs/size`, so a path the client named outside `allowed-upload-prefixes` must be rejected before either touches the disk. `check-media-size` leans on that and answers a file it cannot read with the path code (`:params-validation`), not `:media-max-file-size-reached`. Keep the order when touching or adding a handler; two branches that reorder these lines conflict.
- Raster metadata is shell-derived with ImageMagick `identify`, verifies detected MIME against the supplied MIME, and swaps dimensions for EXIF orientations 6/8.
- Remote image download requires 2xx status, `content-length`, a known MIME, and size under the configured maximum before writing the temp file; mismatched byte count is an internal error.
- Font processing shells out to FontForge and WOFF conversion tools and can derive TTF/OTF/WOFF variants from uploaded fonts.

## File data persistence

- Read-time migrations, fragments, snapshots and restore: `mem:backend/file-data/reads-and-versions`.
- Partial save acceptance and failure handling: `mem:backend/file-data/save`.
- Blob references, offload and partition-aware storage lookups: `mem:backend/file-data-storage`.
