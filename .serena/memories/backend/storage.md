# Backend Storage

## Abstraction

- `app.storage` stores binary objects.
- Each object has a `storage_object` database row.
- The row stores the UUID, size, backend, timestamps, and Transit metadata.
- The backend stores the binary content.
- Supported backends are `:fs` and `:s3`.
- FS uses one root directory and a UUID-derived path.
- S3 uses one configured bucket and an optional prefix.
- A Penpot bucket is metadata. It is not an S3 bucket or a filesystem directory.
- FS and S3 use the same UUID-derived object path. The bucket does not change the path.
- `PENPOT_OBJECTS_STORAGE_*` configures the current object backend.
- Deprecated asset-storage config keys remain supported for migration.
- Database rows keep the backend name. Keep the legacy `:assets-fs` and `:assets-s3` aliases.

## Object Lifecycle

- `put-object!` creates the database row before it writes backend content.
- Backend content is written only when the row is new.
- A failed backend write can leave an unreferenced database row.
- Callers often set `:touched-at` so garbage collection can remove such rows.
- `get-object` excludes rows with `deleted_at`.
- Existing object values can remain readable until physical deletion.
- `:expired-at` blocks reads after the expiration time.
- `del-object!` sets `deleted_at` on live rows only (`deleted_at IS NULL`): a repeated call returns `false`. It does not remove backend content.
- `storage-gc-deleted` removes the database row and backend content after the deletion delay, processing the pending objects in chunks of 25 with keyset pagination (`(deleted_at, id)` cursor, matching the `storage_object(deleted_at, id)` index). Only `status = 'valid'` objects are read: pending ones (still being written, see the status lifecycle) stay untouched even when already marked as deleted. Each chunk is one short read-write transaction: `SELECT ... FOR UPDATE SKIP LOCKED` (so two concurrent runs never take the same chunk; no separate read transaction exists and the run does not block the table vacuum) followed by the database delete and the backend delete grouped by backend. The cursor advances to the `(deleted_at, id)` of the last row read regardless of the deletion result, so a chunk that fails is skipped and not visited again in the same run: the error aborts the transaction (nothing of the chunk is deleted, locks are released), the handler catches it inside the transaction function and rolls it back explicitly — the commit that `tx-run!` performs afterwards is a JDBC-driver no-op on the already-rolled-back connection — and the loop continues with the returned rows. Accepted quirks, both benign and self-healing: (1) with rows of several backends in one chunk, a failure after one group's physical delete leaves those rows pending with already-removed content — pending rows are excluded from reads and the next run finishes them, since the physical delete is idempotent (S3 ignores missing keys, FS uses `deleteIfExists`); (2) rows locked by a concurrent run are skipped via `SKIP LOCKED` and deferred to the next scheduled run, and the `deleted_at <= now` cutoff is sampled per chunk. It emits the `penpot_storage_gc_deleted_objects_total{backend}` counter (one increment per deleted backend group, reported only after the chunk commits, so rolled-back chunks are never counted twice); run duration is covered by the generic `penpot_tasks_timing{name="storage-gc-deleted"}` histogram and chunk failures by the log-derived `penpot_log_events_total`. Every 5000 deleted objects the run pauses 5 seconds (throttle between chunk transactions, never holding one open). It runs as the `storage-gc-deleted` job (`::job-def`) and beats `jobs/heartbeat` after each committed chunk, so a long sweep keeps its lease and never holds it during a chunk transaction.
- `storage-gc-touched` finds references before it sets `deleted_at`.
- Orphan watch: `SELECT COALESCE(metadata->>'bucket', metadata->>'~:bucket') AS bucket, count(*) FROM storage_object so WHERE touched_at IS NULL AND deleted_at IS NULL AND status = 'valid' AND NOT EXISTS (SELECT 1 FROM upload_session_chunk usc WHERE usc.object_id = so.id) GROUP BY 1;` Only the `upload-session` row of that output is a leak signal (its rows always have a mapping or a pending touch/deleted mark); other buckets use domain-table references, not this mapping.
- `objects-gc` removes deleted domain rows and touches their storage object IDs. Each proc streams its pending keys with `db/plan` inside a read-only transaction and deletes them in chunks, each chunk in its own transaction with `FOR UPDATE SKIP LOCKED`: a chunk that fails is logged and skipped instead of aborting the run.
- Use `::db/reuse-conn true` with `sto/resolve` inside a database transaction.

## GC Cost Model

- `sto/touch-object!` is one `UPDATE storage_object SET touched_at`: DB-only, never touches S3/FS backend content.
- Physical FK cascades are avoided by convention; domain deletion is logical (`deleted_at`) everywhere. GC removes small objects first, then parents, then grandparents (see the call order in `app.tasks.objects-gc/execute-objects-gc`). Per-row delete cost is small and bounded.
- `objects-gc` sweeps in chunks of 100 (`::chunk-size`, overridable per job params); `storage-gc-deleted` in chunks of 25. `objects-gc` (and the other `db/plan` sweeps) stream the pending keys in a read-only transaction of their own and delete every chunk in a transaction of its own: the cursor fetch and the deletes are never the same transaction. `storage-gc-deleted` has no separate cursor transaction: it reads, locks and deletes each chunk in the same short transaction (see the keyset pagination above).
- Consequence: per-chunk cost is DB-only and bounded, so these sweeps cannot outrun the 30-minute jobs lease in practice (would need millions of pending rows in one proc). Per-chunk `heartbeat` where present is belt-and-braces, not load-bearing.

## Connection Reuse Details

### `app.storage/resolve` patterns:

**1. Pool mode (default)** - `(sto/resolve cfg)`
- Returns storage abstraction from config
- Uses whatever database pool is available
- **Safe to call outside transaction context**
- Used in: `rpc/commands/media.clj:363`, `rpc/commands/auth.clj:327`, `rpc/commands/profile.clj:362`

**2. Connection reuse mode** - `(sto/resolve cfg ::db/reuse-conn true)`
- Internally calls `db/get-connection cfg` to obtain connectable
- Configures storage with the specific connection from config
- **Must be paired with transaction that owns this connection**
- Used in: `features/fdata.clj:100`, `rpc/commands/media.clj:425`, `rpc/commands/files_thumbnails.clj:307,319`, `binfile/v3.clj:722`

**3. Explicit configuration** - `(sto/configure storage conn)`
- Sets `::db/conn` on storage map directly
- Asserts `db/conn? connection` (storage.clj:349)
- Used inside `db/tx-run!` blocks where `conn` is already available
- Used in: `tasks/file_gc.clj:256`, `rpc/commands/files_thumbnails.clj:347,371`

### Key Warning (from function notes):

The improved note in `import-storage-objects` and `handle-persistence` warns: **Do not reuse the main database connection for storage operations within a transaction.** The storage upload process can fail mid-operation, leaving orphaned objects on the backend. If the outer transaction aborts, pending storage objects become unreconciliable because the storage subsystem registers its pending state in separate transactions.

### Rule of Thumb for `sto/put-object!`:

Since `put-object!` uses backend-specific operations (`impl/resolve-backend` + `impl/put-object`) and does not directly use `::db/conn` or `::db/pool`, **all usage of `put-object!` will never run inside a common transaction** (if configured at all). The storage backend operations are independent of the database transaction boundary.

## Deduplication

- Deduplication requires `::sto/deduplicate?`, a content hash, and bucket metadata.
- The lookup matches hash, bucket, backend, and `deleted_at IS NULL`.
- The lookup only considers rows with `status='valid'`; pending rows are invisible.
- A hit whose blob is missing is repaired in place: the same row/id is kept, and `put-object!` rewrites the blob under that id. This heals all existing references to the object. If the rewrite fails, the row is left live and valid for a later retry.
- The lookup does not include file ID, profile ID, team ID, or organization ID.
- Objects can therefore share content across users and files within one bucket.
- Deleted objects are not reused.
- `tempfile` objects never use deduplication, even when the caller requests it.
- `job-resource` objects never use deduplication either, whatever the caller asks: the object belongs to one profile, and a shared blob would serve the artifact of one user to the job of another. `put-object!` excludes the bucket from the dedupable set, so the invariant does not depend on call sites.
- Use `sto/wrap-with-hash` when the caller already calculated the content hash.

## Bucket Rules

| Bucket | Content and references | Dedup | Direct `/assets/by-id` access | Cleanup |
| --- | --- | --- | --- | --- |
| `file-media-object` | Original file images and generated media thumbnails. References: `file_media_object.media_id` and `thumbnail_id`. | Yes | Public | Reference scan. |
| `team-font-variant` | Font variants in `team_font_variant`. References: `woff1_file_id`, `woff2_file_id`, `otf_file_id`, and `ttf_file_id`. | Yes | Public | Reference scan. |
| `file-object-thumbnail` | Frame and component thumbnails in `file_tagged_object_thumbnail.media_id`. | Yes | Public | Reference scan. |
| `file-thumbnail` | File grid thumbnails in `file_thumbnail.media_id`. | Yes | Authentication required | Reference scan. |
| `profile` | User and team profile photos. References: `profile.photo_id` and `team.photo_id`. | Yes | Authentication required | Reference scan. |
| `organization` | Organization logos uploaded by the Nitrate management API. | Yes | Public | No reference scan. A touched object is deleted. |
| `tempfile` | Export files and temporary font downloads. | No | Authentication required | No reference scan. A touched object uses a two-hour deletion delay. |
| `upload-session` | Chunked-upload chunks. References: `upload_session_chunk.object_id` and `upload_session_chunk.session_id` (both NO ACTION DEFERRABLE: restrict semantics, procedural deletion). | No | Authentication required | Reference scan on `upload_session_chunk.object_id`; a referenced chunk freezes, an orphan is deleted after the delay. Deferred rows keep their mappings until give-up. |
| `file-data` | Encoded file data when `file-data-backend` is `storage`. Reference: the `file_data.storage_object_id` column (FK `NO ACTION DEFERRABLE INITIALLY IMMEDIATE`); the object metadata keeps `file-id` and the `file_data` row ID. | Yes | Authentication required | Reference scan. |
| `file-data-fragment` | Compatibility value for file-data fragments. The current backend has no dedicated producer for this bucket. | No current write semantics | Public | No touched-object collector case. |
| `file-change` | Compatibility value for file changes. Current snapshots store data in `file_data`, not this bucket. | No current write semantics | Authentication required | No touched-object collector case. |
| `job-resource` | Storage objects owned by job rows (`job.resource_id`), written by `app.jobs.storage`. | No | Authentication required, owner-scoped | jobs-GC touch → reference scan. |

- The valid bucket set is `app.storage.schema/metadata-buckets` (derived from `bucket-requirements`, where a new bucket and its required metadata keys are one entry); `app.storage/valid-buckets` aliases it.
- `file-media-object` is the default bucket for old rows without bucket metadata.
- Do not assign a new bucket without adding its access and cleanup behavior.
- The touched-object collector raises an internal error for an unknown bucket.
- It supports `file-media-object`, `team-font-variant`, `file-object-thumbnail`, `file-thumbnail`, `profile`, `file-data`, `tempfile`, `upload-session`, `job-resource`, and `organization`.
- It does not support `file-data-fragment` or `file-change`.

## Access Rules

- `app.http.assets` decides direct object authentication from the bucket.
- Public buckets are `file-media-object`, `file-object-thumbnail`, `team-font-variant`, `file-data-fragment`, and `organization`.
- Other valid buckets require a session or access-token profile ID.
- `tempfile` and `job-resource` are owner-scoped: the request must come from the profile stored in the object metadata, and a mismatch answers 404 (counted as `unauthorized`). A missing owner is tolerated only in `tempfile`, where legacy objects predate the metadata; `job-resource` always carries one, so an object without it is never served.
- File-media routes also require file read permission.
- Non-public direct responses set `content-disposition: attachment`.
- FS responses use `x-accel-redirect` for the configured asset path.
- S3 responses use a presigned URL and an HTTP redirect.

## File Data

- `file-data-backend` accepts `legacy-db`, `db`, or `storage`.
- `legacy-db` stores main data in `file.data` and snapshots in `file_change.data`.
- `db` stores encoded data in `file_data.data`.
- `storage` stores encoded data in storage subsystem with `file-data` bucket, keeps `data` nil in `file_data` table, and points at the object with `file_data.storage_object_id`.
- `file_data.storage_object_id` is a `NO ACTION DEFERRABLE INITIALLY IMMEDIATE` foreign key to `storage_object(id)`: the database refuses to physically delete a referenced object. The `IMMEDIATE` is load-bearing: the RI violation fires inside the `storage-gc-deleted` chunk transaction, so the chunk rolls back, is logged at error level with a generic hint plus the `constraint` and `table` the database reports (any `NO ACTION` foreign key on `storage_object` can block the delete, not only `file_data`'s) and is retried on the next run; with `INITIALLY DEFERRED` the check would fire at commit, outside the catch, and the sweep would never move past the blocked chunk.
- The column is the only source of truth. The release is a coordinated restart (no rolling deploy) when the instance uses the storage backend (`PENPOT_FILE_DATA_BACKEND=storage`) or still has rows carrying `metadata.storage-ref-id`; with the default `db` backend and no such rows the previous and the new code read the same `file_data.data` column, so a rolling deploy is safe. `metadata.storage-ref-id` is no longer written or read, and the `file-data` metadata schema does not declare it. The migration backfills the rows it can resolve (valid uuid with an existing `storage_object`) and removes the key from those rows; a corrupt or orphan pointer keeps the key, which nothing reads. A large instance runs `operations/2.20.0-file-data-storage-object-id.md`: column, index and FK online, then the backfill (only if a row still carries `metadata.storage-ref-id`) with every instance stopped, verifies, and marks the migration as applied (`INSERT INTO migrations`). The backfill must never run while the previous code serves when the storage backend is in use (or rows with the pointer remain), because that code writes and scans the metadata pointer. A rollback to the previous code cannot read the rows written by this release when the storage backend is in use.
- `fdata/upsert!` touches the `storage_object_id` it receives in the params (the pointer the persisted row had) before replacing it, so the replaced blob is reclaimed. Callers pass the column from the file map (main/restore), `nil` for a new row (fragments, snapshots, new files) or the row itself (offload); there is no query in the upsert path.
- `binfile.common/update-file!` takes the file map read from the database plus the fields to change (`[cfg file & params]`), merges them and builds the `file_data` params from the merged map, so the pointer travels in the read map and a caller cannot lose it by building a partial map; the params cannot override `:storage-object-id`. `insert-file!` always drops it (an INSERT never replaces a persisted pointer; `duplicate-file` hands the source map there). `bfc/get-file` keeps the pointer for the write paths, and every response goes through `bfc/file->response`, which strips it and any residual `storage-ref-id` left in the metadata of the rows the migration does not backfill: each `file-data` object is authentication-only and any logged-in profile could otherwise fetch it from `/assets/by-id`.
- The in-place binfile import (`binfile/v3.clj` `import-file-and-overwrite*`, the `::overwrite` branch of `save-file!`) is legacy dead code and is not supported by the pointer model: it passes `nil` and would leave the old object unreclaimed. It is pending removal in a separate PR.
- File snapshots use `file_data` for snapshot data and `file_change` for snapshot metadata.
- Tiered offload (`:tiered-file-data-storage` flag + `db` backend): `file-gc` submits an `:offload-file-data` job (priority 10, delay 1000, same params) only when it actually processed the file; the handler takes the `file` row with `FOR UPDATE SKIP LOCKED` and skips (`{:skipped 1}`, no error) when the row is locked or gone, then moves every live `file_data` row of that file with `backend = 'db'` to `backend = 'storage'` via `fdata/upsert!` (one `put-object!` in bucket `file-data` per row, `data` becomes nil and `storage_object_id` points at the new object) inside a single all-or-nothing transaction per file. The skip only serializes because every `file_data` writer holds at least `FOR SHARE` on the `file` row. A storage failure aborts only that file and bubbles for a runner retry; orphans go to `storage-gc-touched`. A second run is a no-op because the query only matches `backend = 'db'`.
- The offload normalizes each `SELECT fd.*` row before `upsert!`: the row builder omits NULL columns, jsonb metadata arrives as a `PGobject`, and a JSON `null` decodes to nil and must disappear instead of travelling as `:metadata nil`, which the update schema rejects.
- Offload pacing: none by deliberate decision. Sleeps only make sense when batching multiple transactions; the per-file transaction is short by construction and the scheduler run takes minutes per production metrics, so neither loop sleeps. `jobs/heartbeat` (pool-based liveness, ~1 write/s) stays on both paths.
- Offload metrics (bounded labels only, never ids or error text): `penpot_file_data_offload_rows_total{type}` and `penpot_file_data_offload_bytes_total{type}` (`type`: `main`/`snapshot`/`fragment`, successful runs only) plus `penpot_file_data_offload_timing_ms{result}` (`result`: `ok`/`skip`/`error`). Helpers live in `app.jobs.metrics`; the job-def requires `::mtx/metrics`.
- Writes always resolve the backend through `fdata/default-backend` (`cf/get :file-data-backend`), so post-offload edits land back in hot (`db`) storage without extra code; reads stay transparent through `fdata/resolve-file-data` + `decode-file-data` + `realize`.
- Deleting an offloaded row touches its storage object first (`objects-gc/delete-file-data-chunk` via `fdata/touch-storage-object` with the row's `storage_object_id`), so `storage-gc-touched` reclaims the blob with the usual delay; restoring a file unmarks the referenced objects.
- `storage-gc-touched` resolves the `file-data` reference with `SELECT EXISTS (SELECT 1 FROM file_data WHERE storage_object_id = ?)`, an indexed lookup that no longer parses the object metadata.
- A row with `backend='storage'` and no readable object makes `resolve-file-data` raise `:missing-storage-object` with the file and row ids instead of failing later in the storage backend.
- The `file-id` and row id kept in the storage object metadata are informational for diagnostics; nothing parses them to resolve the reference (the reference is the column).

## File Change

- `file_change` is partitioned by `HASH (file_id)` in 16 partitions, with the same bounds as `file_data`, so a join between them is resolved partition by partition (`enable_partitionwise_join` is set on every pooled connection).
- The automatic migration (`0158-partition-file-change`) refuses to run when `file_change` is above 10 GiB total size or 2M rows (`pg_total_relation_size` + `pg_class.reltuples`, catalog reads). It analyzes the table first, because `reltuples` is -1 without statistics. It raises with the path of `operations/2.20.0-file-change-partition.md`, because the copy happens inside the migration transaction and a big instance would restart in a loop. Below the guard it raises `statement_timeout` to 30 minutes for the copy and the index builds, and analyzes the new partitions at the end, after the copy and the indexes, so the planner does not start with empty statistics.
- Its primary key is `(file_id, id)`: a partitioned table needs the partition key in the primary key, so every access filters by `file_id`. From 2.20 the four snapshot commands (`update-file-snapshot`, `delete-file-snapshot`, `lock-file-snapshot`, `unlock-file-snapshot`) require the `file-id` param, because they use it to prune the partition; without it they answer `:params-validation`.
- It has a `kind` column: `1` is a snapshot (it has a label and its data lives in `file_data`) and `2` is a revision (it has no label and it holds the changes). The values are in `app.features.file-changes`.
- `kind` is a deliberate reserve, not a leftover: today no query filters by it, the queries still tell a snapshot from a revision by its label (`label IS NOT NULL`), and `kind` takes over when they are rewritten. No constraint ties `kind` and `label` together on purpose: the day `kind` is the source of truth, that constraint would have to go. It stays nullable on purpose: the previous version inserts without it, and a rollback to it has to keep writing; the `NOT NULL` comes in a later migration.
- Every write of the application to a row of `file_change` moves `updated_at` to the moment of the write (`lock-by!`, `unlock!`, the snapshot rename and delete, `delete-object`, the restore of a file, and the `legacy-db` snapshot write of `fdata`). The actions of the foreign keys (`ON DELETE SET NULL` on `locked_by` and `profile_id`) change the row without touching it; during the partition both tables carry the same keys, so both receive them. The runbook of the partition uses it as the watermark of its delta passes: a write of the application that leaves it behind is lost at the cutover.
- `file_change_legacy` is a frozen copy of the previous layout of the partition: it keeps the rows and the indexes of the old table. A new instance (empty table) does not keep it. On an upgraded instance the table stays until the operator drops it by hand days later (`DROP TABLE file_change_legacy;`, see `operations/2.20.0-file-change-partition.md`); nothing drops it automatically.
- A revision carries only the delta of a save (the changes). The features of the file are not repeated on it: nobody read them from there.
- A snapshot carries the features and the migrations of the file.
- It keeps the `data` column: the `legacy-db` backend is still supported (migration `0157-migrate-legacy-file-data` moved the existing blobs, but the backend keeps reading and writing that column). It has no `data_ref_id` or `data_backend` columns: nothing read them since the file storage refactor.
- It has no index for the system snapshots. The old one filtered them by `data IS NOT NULL`, and with the payload living in `file_data` the column no longer tells snapshots apart. The new one could only filter them by `deleted_at IS NULL`, but the system snapshots always carry a deletion date in the future, so it would never match a row. The query that would use it filters that date with an "or", which no partial index can serve.
- The `objects-gc` purge groups each chunk by `file_id` and locks it with `file_id = ?` and `id = ANY(?::uuid[])`: a tuple `IN ((file_id, id), ...)` gives the planner no constant partition key, so it scans all 16 partitions in every chunk. Each group is one `FOR UPDATE SKIP LOCKED` query inside the chunk transaction.

## Metrics

- `bucket` is always the Penpot logical bucket (object metadata), never an S3 bucket. Unknown/absent buckets are labeled `"unknown"`.
- `target` is the physical S3 destination id. Today it is always `"default"` (hardcoded in `app.storage.s3/build-s3-client`; the `::target-id` config key was removed as unused until the per-bucket routing plan lands).
- Physical S3 API calls (AWS SDK `MetricPublisher`, `app.storage.s3.metrics`):
  - `penpot_storage_s3_requests_total{operation,target,result}` — one count per logical SDK call (the published `ApiCall` collection, not per attempt); retries are counted apart in `retries_total`, so total attempts = `requests + retries`. `result` is `"ok"` only when the SDK reports success as exactly `true`; a missing success flag counts as `error`.
  - `penpot_storage_s3_retries_total{operation,target}` — SDK retry count.
  - `penpot_storage_s3_timing{operation,target}` — call latency histogram (ms); explicit buckets up to 60000 ms (S3 slow calls exceed the default 7500 ms cap).
- Logical storage operations (`app.storage`, `::mtx/metrics` required by the schema):
  - `penpot_storage_operations_total{op,bucket,backend}` — `put`, `repair`, `get-data`, `get-bytes`, `del`, `touch`, `exists`. All ops are success-only: `put`/`repair` emit after the backend write, `get-*` after the backend fetch opens, `touch`/`del` only when a row actually changed. `touch-object!`/`del-object!` take the object id (UUID) only — no object overload. Labels come from the updated row itself via `UPDATE ... RETURNING id, backend, metadata` (no extra `SELECT`); with no row matched they emit nothing. `del-object!` only matches live rows (`deleted_at IS NULL`): a repeated del returns `false` and emits nothing. Post-open stream read errors stay counted as attempts. `del` only marks `deleted_at`; physical deletion is a GC concern. `exists` is emitted per deduplication-hit probe, always paired with a `hit`/`repair` outcome (never on probe failure), not per user-facing existence check.
  - `penpot_storage_dedup_total{result,bucket}` — `hit`, `miss`, `repair`, `skip`.
- Storage GC poison (`app.storage.gc-touched`, `::mtx/metrics` required in the handler cfg):
  - `penpot_storage_gc_poison_total` — touched rows whose metadata fails to decode. Each one is logged at error level and deferred exactly one day instead of aborting the chunk. No labels; incremented once per row per scan.
- Asset serving (`app.http.assets`, `::mtx/metrics` required in the handler cfg):
  - `penpot_storage_asset_requests_total{route,backend,bucket,result}` — `route` is `by-id`, `by-file-media-id`, or `thumbnail`; `result` is `served` (<400), `not-found` (404), `unauthorized` (401/403), or `error` (everything else, including a nil/non-number status: every serve path must set `::yres/status`). Serve-path exceptions are counted by `serve-object-measured` and then rethrown. Permission-denied file-media requests and tempfile ownership mismatches both answer HTTP 404 (to avoid leaking existence) but are counted as `unauthorized`. Malformed UUIDs raise before any emission point and are never counted. Counts backend requests that trigger a browser GET to the object store (one per cache miss), so it is a proxy for object GETs, not an exact count.
- The physical and logical counters intentionally overlap in coverage but differ in meaning; do not sum them.
- Metrics is not optional: `::mtx/metrics` is required by the storage and s3-backend schemas, and the assets handler cfg always carries it. Wiring a component without metrics is a bug, not a supported mode.
- Recording never fails: `app.metrics/run!` is safe by default at every emit point (`emit-op!`, `emit-dedup!`, `emit-asset!`, and the three S3 publisher emissions). The first recording failure per metric id logs at `warn`, later ones at `debug` (no log flood). The `instance` precondition is a plain assert and the collector lookup is outside the recording guard, so a missing instance fails hard (see `mem:backend/subtleties`). The `publish` outer try/catch stays: it is an SDK `MetricPublisher` contract boundary, not a metrics guard.
