# Backend storage

- Start at `app.storage` for metadata and upload lifecycle, `app.storage.schema` for logical buckets, `app.http.assets` for access, and `app.storage.{gc-touched,gc-deleted,pending-gc}` for cleanup. A Penpot bucket is metadata, not an S3 bucket or a filesystem path. Keep legacy backend names readable because persisted rows select their backend.

## Uploads, references and cleanup

- `put-object!` uses the storage pool, not the caller's transaction, to register a `pending` row, write the blob and promote the row. Resolving storage with `::db/reuse-conn true` does not put the upload inside the caller's transaction. Use connection reuse for metadata operations that must see or participate in that transaction; do not carry an owned connection outside its scope.
- If the upload completes but the caller fails to attach a reference, a `valid` object marked `touched` is checked by `storage-gc-touched`. The collector checks references for the logical bucket before marking an object for deletion. Preserve touch-on-create at call sites that rely on this recovery. If an upload never promotes its row, `storage-pending-gc` owns cleanup instead; normal reads, deduplication and GC skip pending rows.
- `objects-gc` touches blobs when it purges their domain references. Keep the read-only scan separate from chunk write transactions, and copy row values before buffering (`mem:backend/core`). `storage-gc-deleted` instead locks and deletes in short keyset chunks. Advance past failed or locked rows in the current sweep so one chunk cannot stall the rest; later sweeps retry. Physical deletion must tolerate a missing blob: rollback of a chunk does not restore bytes already removed.
- A valid, unmarked upload object without an `upload_session_chunk` mapping is a leak signal **only for the `upload-session` bucket**. Other buckets have different references. Keep poison metadata rows from blocking an entire touched-object chunk; see the deferral path in `app.storage.gc-touched`.

## Buckets, deduplication and access

- A dedup hit is scoped by content hash, logical bucket and backend, not by user or file. It does not confer permission. If the shared blob is missing, repair it under the same ID so existing references survive. `tempfile`, `upload-session` and `job-resource` must stay out of deduplication.
- When adding a bucket, check the metadata schema, direct-asset access and touched-object handling together. Old rows with no bucket fall back to `file-media-object`; the compatibility buckets `file-data-fragment` and `file-change` are not current producers and have no touched-object collector case.
- `tempfile` and `job-resource` require the owning profile; mismatches return 404. Only legacy `tempfile` can lack an owner. Job retention controls the lifetime of job resources; a touched resource remains protected while any job references it. See `mem:backend/jobs`.
- Do not expose `file-data` object IDs as file access tokens. Authentication on the direct asset route does not replace file permissions; see `mem:backend/file-data-storage`. The bucket access matrix and redirect behavior live in `app.http.assets`.

## Metrics

- Logical storage operations, physical S3 SDK calls and asset HTTP requests count different things; do not add them together. `bucket` is logical object metadata; S3 `target` is the destination. Read metric definitions from `app.storage`, `app.storage.s3.metrics`, the GC jobs and `app.http.assets`. Wire metrics into the components, but do not let a recording failure change storage behavior.
