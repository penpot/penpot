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
- `del-object!` sets `deleted_at`. It does not remove backend content.
- `storage-gc-deleted` removes the database row and backend content after the deletion delay.
- `storage-gc-touched` finds references before it sets `deleted_at`.
- `objects-gc` removes deleted domain rows and touches their storage object IDs.
- Use `::db/reuse-conn true` with `sto/resolve` inside a database transaction.

## Deduplication

- Deduplication requires `::sto/deduplicate?`, a content hash, and bucket metadata.
- The lookup matches hash, bucket, backend, and `deleted_at IS NULL`.
- The lookup does not include file ID, profile ID, team ID, or organization ID.
- Objects can therefore share content across users and files within one bucket.
- Deleted objects are not reused.
- `tempfile` objects never use deduplication, even when the caller requests it.
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
| `tempfile` | Export files, chunked-upload chunks, and temporary font downloads. | No | Authentication required | No reference scan. A touched object uses a two-hour deletion delay. |
| `file-data` | Encoded file data when `file-data-backend` is `storage`. Reference metadata has `storage-ref-id`, `file-id`, and the `file_data` row ID. | Yes | Authentication required | Reference scan. |
| `file-data-fragment` | Compatibility value for file-data fragments. The current backend has no dedicated producer for this bucket. | No current write semantics | Public | No touched-object collector case. |
| `file-change` | Compatibility value for file changes. Current snapshots store data in `file_data`, not this bucket. | No current write semantics | Authentication required | No touched-object collector case. |

- The valid bucket set lives in `app.storage/valid-buckets`.
- `file-media-object` is the default bucket for old rows without bucket metadata.
- Do not assign a new bucket without adding its access and cleanup behavior.
- The touched-object collector raises an internal error for an unknown bucket.
- It supports `file-media-object`, `team-font-variant`, `file-object-thumbnail`, `file-thumbnail`, `profile`, `file-data`, `tempfile`, and `organization`.
- It does not support `file-data-fragment` or `file-change`.

## Access Rules

- `app.http.assets` decides direct object authentication from the bucket.
- Public buckets are `file-media-object`, `file-object-thumbnail`, `team-font-variant`, `file-data-fragment`, and `organization`.
- Other valid buckets require a session or access-token profile ID.
- File-media routes also require file read permission.
- Non-public direct responses set `content-disposition: attachment`.
- FS responses use `x-accel-redirect` for the configured asset path.
- S3 responses use a presigned URL and an HTTP redirect.

## File Data

- `file-data-backend` accepts `legacy-db`, `db`, or `storage`.
- `legacy-db` stores main data in `file.data` and snapshots in `file_change.data`.
- `db` stores encoded data in `file_data.data`.
- `storage` stores encoded data in storage subsystem with `file-data` bucket and keeps `data` nil in `file_data` table.
- The `file_data.metadata.storage-ref-id` value points to the storage object.
- `fdata/upsert!` touches a storage object from incoming metadata before it stores the new row.
- File snapshots use `file_data` for snapshot data and `file_change` for snapshot metadata.
