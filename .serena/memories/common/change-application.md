# Common change application

- Start at `app.common.files.changes` and the shape-tree helpers. `process-changes` validates the change vector once by default, reduces it and then processes collected touched changes; `verify? false` is for callers that already validated. Component-specific touched and copy rules are in `mem:common/components`.
- `:set` delegates to `ctn/set-shape-attr`; `:assign` decodes attrs before applying per-attr sets.
- `shape-tree/add-shape` falls back invalid parents or frames to root and keeps parent `:shapes` a vector without duplicate IDs. `delete-shape` removes descendants from the objects map as well as unlinking the parent; render-wasm deletion has different undo internals.
- Page-object maps can carry metadata indexes. Rebuild them with `start-page-index` / `update-page-index` as appropriate; frontend commit application calls `ctst/update-object-indices` after page changes. For change construction and undo see `mem:common/changes-architecture`.
- `update-file` also uses change-scoped schema validation: `touched-page-ids` selects pages while non-page data is checked whole. Register new change types as page-writing or file-level; unknown types fall back to the full schema check, which loads every page.
