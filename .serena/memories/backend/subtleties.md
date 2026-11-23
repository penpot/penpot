# Backend Subtleties

## RPC exposure and wrappers

- RPC commands are discovered from vars created by `app.util.services/defmethod`; adding a command namespace is not enough unless `backend/src/app/rpc.clj` includes it in `resolve-methods`.
- `GET`/`HEAD` RPC calls are only allowed for method names starting with `get-`. Other methods are method-not-allowed even if they are read-only internally.
- RPC auth defaults to enabled. Public endpoints must set `::auth false` metadata explicitly.
- The wrapper stack does auth before params validation, then auditing/rate/concurrency/metrics/retry/condition handling, with DB transaction handling inside that stack. `::db/transaction` metadata controls transaction wrapping.
- Params with `::sm/params` are decoded/conformed through the JSON transformer and successful IObj results get `:encode/json` metadata. Legacy spec conforming only applies when no Malli params schema exists. Client params are stripped of qualified keys (`d/without-qualified`) before merging with the server auth context, so request bodies cannot override `::profile-id`, `::auth-type`, or `::token-perms`.
- Params schemas are open by default, so undeclared client keys reach the handler unless the map is `:closed true`. Creation commands (`create-file`, `create-project`, `create-team`, `create-team-with-invitations`, `upload-file-media-object`, `create-file-media-object-from-url`, `assemble-file-media-object`) use closed schemas: a client-provided `:id` fails with `:params-validation`. Their internal creation functions still accept an optional explicit `:id` for imports, duplicates and deterministic test fixtures.
- Nil RPC bodies become HTTP 204 unless explicit status metadata is present. Stream bodies default to `application/octet-stream` when no content type is set.
- The upload schema (`media.v/schema:upload`) takes a `:path` from the caller, and a Transit body can tag any string as a `path`, so a command that reads such an upload reads a file the client named. `create-import-binfile-job` avoids that boundary by accepting only a chunked upload (`:upload-id`); the commands that still take a plain file (the legacy `import-binfile`, the media and font uploads) trust it, and the guard belongs where uploads are accepted, not in each command.

## Concurrency limiter (CLIMIT)

- Limit declarations, acquisition order and queue diagnosis: `mem:backend/rpc-concurrency`.

## DB helpers

- Transaction ownership, callbacks and advisory locks: `mem:backend/db-transactions`.

## Workers and cron

- Job lifecycle, recovery and cron overlap: `mem:backend/jobs`.
- Startup failures in injected component schemas: `mem:backend/integrant-schemas`.

## Config and HTTP/session middleware

- `app.config/config` and `flags` are dynamic `defonce` vars populated from `PENPOT_*` env vars through the shared schema string transformer. Tests and tooling can bind them.
- Duration config values are parsed as ISO-8601 with the `PT` prefix, so a day is written in hours (`:jobs-retention` and `:jobs-user-ttl` default to a week, and an operator writes `168h`): `7d` does not parse, and the value fails validation at boot instead of being silently ignored.
- `app.main` wires `PENPOT_DATABASE_*` into `::db/pool`, and `app.db/create-datasource-config` forwards pgjdbc's own knobs as `dataSourceProperties`, which `Properties.getProperty` silently drops unless the value is a String (hence the `str`). A query parameter of the same name in `PENPOT_DATABASE_URI` (for example `?prepareThreshold=0`) wins over the property: pgjdbc ignores the Properties value when the URL carries it, so the URI is the place to look when a variable seems to do nothing. The prepared statement cache is three optional `PENPOT_DATABASE_PREPARED_STATEMENT_CACHE_*` keys: the threshold is the executions before a statement is server-prepared and accepts `-1` or non-negatives (`{:min -1}`; the driver clamps any other negative to `-1`, so rejecting it only turns a typo into a boot error), its `0` stops server-side prepared statements while the driver's client-side cache of parsed queries stays in use, `..._QUERIES` and `..._SIZE` reject negatives and `0` in either disables that cache, and the size is bytes rounded up to the whole MiB the driver accepts. Do not describe the threshold `0` as disabling the cache: it only stops the server-side prepares.
- `parse-flags` automatically adds `:disable-secure-session-cookies` when `public-uri` is plain HTTP and not localhost. This changes cookie defaults without an explicit env flag.
- The backend sets Clojure `*assert*` globally from the `:backend-asserts` feature flag. Assertion-dependent checks can therefore differ by runtime flags.
- Request body parsing is mostly POST-oriented and supports Transit JSON plus plain JSON. Plain JSON request keys are kebab-decoded before being merged into `:params`. A body the parser cannot read is a client error, classified at the parse site and never by exception class (`clojure.data.json` signals its syntax errors with a bare `java.lang.Exception`): 400 `:validation`/`:malformed-json`, one `warn` line (`malformed request body`) carrying path, peer address and content-type instead of a stack trace, and `penpot_http_malformed_requests_total`. `RequestTooBigException` keeps its 413.
- Response formatting negotiates with `Accept` or `_fmt=json`. Transit is the default for collection/boolean bodies; JSON encoding has special pointer-map handling.
- Auth prefers the session cookie token before the `Authorization` header. Headers may be `Token` or `Bearer`. Only `kid=1`/`ver=1` tokens are decoded as session tokens; anything else is left unauthenticated (legacy v1 tokens were removed).
- Shared-key auth requires `x-shared-key` as `<key-id> <key>` and stores the lowercased key id on the request. If no shared keys are configured it always rejects. Management methods declare the key ids they accept with `::rpc/allowed-shared-keys`; a valid key outside that set answers 403 `:shared-key-not-allowed`, and a method without the metadata accepts any configured key.
- Session management uses DB storage unless the DB pool is read-only, then falls back to the in-memory manager. Sessions use only the v2 UUID model (`http_session_v2`); legacy string ids were removed.
- Session cookies are renewed when `modified-at` is older than the 6h renewal interval. SameSite is `none` for CORS, otherwise strict/lax based on config. Session lifetime config and GC: `mem:backend/session-expiration`.

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
