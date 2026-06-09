# Common File Change, Validation, and Migration Subtleties

## Change application

- `process-changes` validates the whole change vector once by default, reduces changes, then performs a second pass for collected touched changes. Callers that already validated can pass `verify? false`.
- `process-operation :set` delegates to `ctn/set-shape-attr`; `:assign` first decodes attrs with the shape-attrs JSON transformer and then emits per-attr set operations.
- `set-shape-attr` treats `:position-data` as derived and never touched. Geometry/content-path changes use approximate equality; geometry differences under about 1px can be ignored for touched purposes.
- Width/height are excluded from the `is-geometry?` branch in `set-shape-attr`; do not assume all geometry-group attrs follow identical ignore-geometry behavior.
- `process-touched-change` marks the owning component modified when a touched shape belongs to a main instance; component-data changes can come from shape ops through this second pass.

## Shape tree edits

- `shape-tree/add-shape` falls back invalid/missing parent or frame ids to root (`uuid/zero`), ensures parent `:shapes` is a vector, avoids duplicate child ids, and clears `:remote-synced` on copy parents unless `ignore-touched` is true.
- `shape-tree/delete-shape` removes the shape and all descendants from the objects map and removes the id from its parent. This is different from render-wasm deletion, which may keep deleted children for undo/redo internals.
- Page object maps can carry metadata indexes such as cached frame lists. `start-page-index` / `update-page-index` rebuild those metadata indexes; `frontend` commit application calls `ctst/update-object-indices` after page changes.

## Validation and repair

- Full referential/semantic validation currently runs only when file features contain `"components/v2"`.
- Validation starts at root plus orphan shapes, then validates component records. `validate-file!` raises `:validation :referential-integrity` with collected details.
- `repair-file` does not mutate data directly; it reduces validation errors into redo changes using `changes-builder`. Callers must apply or persist those changes.

## Migrations

- Prefer optional attrs/default behavior so old files continue working without migration. If absence cannot preserve old behavior, add a migration.
- Migrations are an ordered set mixing legacy version-derived ids and newer named ids. Keep append order stable; `migrate` applies the set difference between available migrations and file migrations.
- `migrate-file` synthesizes legacy migration ids from old numeric versions when `:migrations` is absent, migrates legacy features, and records feature flags created through `cfeat/*new*`.
- When a file had no previous `:migrations`, `migrate-file` marks all migrations as migrated in metadata so callers persist the complete migration set, not only transformations that changed data.