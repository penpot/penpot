# Backend HTTP, Storage, Media, and File Data Subtleties

## Config and HTTP/session middleware

- `app.config/config` and `flags` are dynamic `defonce` vars populated from `PENPOT_*` env vars through the shared schema string transformer. Tests and tooling can bind them.
- `parse-flags` automatically adds `:disable-secure-session-cookies` when `public-uri` is plain HTTP and not localhost. This changes cookie defaults without an explicit env flag.
- The backend sets Clojure `*assert*` globally from the `:backend-asserts` feature flag. Assertion-dependent checks can therefore differ by runtime flags.
- Request body parsing is mostly POST-oriented and supports Transit JSON plus plain JSON. Plain JSON request keys are kebab-decoded before being merged into `:params`.
- Response formatting negotiates with `Accept` or `_fmt=json`. Transit is the default for collection/boolean bodies; JSON encoding has special pointer-map handling.
- Auth prefers the session cookie token before the `Authorization` header. Headers may be `Token` or `Bearer`; JWTs with `kid=1` and `ver=1` are decoded as v1 session tokens, otherwise they are treated as legacy tokens.
- Shared-key auth requires `x-shared-key` as `<key-id> <key>` and stores the lowercased key id on the request. If no shared keys are configured it always rejects.
- Session management uses DB storage unless the DB pool is read-only, then falls back to the in-memory manager. DB sessions support both legacy string ids and v2 UUID session ids.
- Session cookies are renewed when using a legacy string id or when `modified-at` is older than the renewal interval. SameSite is `none` for CORS, otherwise strict/lax based on config.

## Storage and media

- Storage has a fixed valid bucket set. Backends are `:fs` and `:s3`; default backend comes from deprecated `assets-storage-backend` only when present, otherwise `objects-storage-backend`, defaulting to `:fs`.
- `put-object!` creates the DB `storage_object` row before writing backend content. Backend writes happen only for newly created rows, so deduplication can skip object writes.
- Deduplication only applies when requested, when the content can provide a hash, and when bucket metadata is present. Reads exclude soft-deleted storage rows.
- `sto/resolve` can reuse the current DB connection via `::db/reuse-conn true`; preserve this in transaction-sensitive code.
- SVG validation strips DOCTYPE and uses secure SAX parsing. Basic SVG info falls back to 100x100 dimensions when width/height/viewBox are missing.
- Raster metadata is shell-derived with ImageMagick `identify`, verifies detected MIME against the supplied MIME, and swaps dimensions for EXIF orientations 6/8.
- Remote image download requires 2xx status, `content-length`, a known MIME, and size under the configured maximum before writing the temp file; mismatched byte count is an internal error.
- Font processing shells out to FontForge and WOFF conversion tools and can derive TTF/OTF/WOFF variants from uploaded fonts.

## File data persistence

- File data backends are `legacy-db`, `db`, and `storage`. The storage backend keeps encoded file data in storage bucket `file-data`; the DB row stores metadata with `storage-ref-id` and nil data.
- `fdata/upsert!` touches any storage object referenced by incoming metadata before storing the new row/blob.
- Pointer-map fragments are persisted separately as type `fragment`, and only modified pointer maps are written.
- `fdata/realize` combines pointer realization and object-map realization. Use it before operations that need complete in-memory file data instead of pointer placeholders.