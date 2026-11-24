# File changes

- File changes are both persistence payloads and the basis for undo. Start at `app.common.files.changes` for schemas and application, `app.common.files.changes-builder` for redo/undo construction and `app.common.files.changes-merge` for compaction. Use the production pipeline rather than editing object maps directly.

## Construction and compaction

- Preserve redo and undo order when compacting: merge `:reg-objects` per container and place registrations after changes that resize children. Never compact across commit boundaries; the backend can reject individual commits by `commit-id`. Client save ordering is in `mem:frontend/workspace-persistence`.
- Do not send a deleted component's entire object map for a metadata-only edit. Scope shape edits to `:component-id` using the builder's container context. Omitted `:objects` preserves stored shapes; explicit nil clears them. Reserve `:objects? true` for repairs that intentionally move or clear the whole map.
- Apply fixture edits through `app.common.test-helpers.files/apply-changes`; disable its validation only for intentionally invalid intermediate states. See `mem:common/testing`.

## Application and schema validation

- `process-changes` validates the change vector once by default, reduces it and then processes collected touched changes; `verify? false` is for callers that already validated. `:set` delegates to `ctn/set-shape-attr`, while `:assign` decodes attrs before per-attr sets. Component touched and copy rules are in `mem:common/components`.
- `shape-tree/add-shape` falls back invalid parents or frames to root and keeps parent `:shapes` a vector without duplicate IDs. `delete-shape` removes descendants from the objects map as well as unlinking the parent; render-wasm deletion has different undo internals.
- Page-object maps can carry metadata indexes. Rebuild them with `start-page-index` / `update-page-index` as appropriate; frontend commit application calls `ctst/update-object-indices` after page changes.
- `update-file` uses change-scoped schema validation: `touched-page-ids` selects pages while non-page data is checked whole. Register new change types as page-writing or file-level; unknown types fall back to the full schema check, which loads every page. Referential integrity and repair are in `mem:common/components`.
