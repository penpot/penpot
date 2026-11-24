# Common Data Model Change Checklist

## Attribute conventions

- Prefer optional page/shape attrs with default behavior when absent. Reverting to default should usually remove the attr instead of storing nil.
- Do not treat nil as a distinct persisted state from absence. Import/export and cleanup paths may filter nil attrs away.
- Avoid Clojure-special naming in exported object attrs, especially boolean names ending in `?`; exported/imported data must survive JSON/SVG/Transit and external tooling.
- Normal shape attrs that participate in component sync belong in `app.common.types.component/sync-attrs` with the correct touched group. `:applied-tokens` participates through a separate route, not `sync-attrs`. See `mem:common/components` for synchronization and touched semantics.
- Store SVG attribute keys in kebab-case (`fill-rule`, not `fillRule`) and convert to React spelling only at render boundaries. Use `app.common.svg/attrs->kebab-props` for shape and defs-node attrs; for svg-raw `:content`, normalize spelling without filtering unknown keys (the sidebar reads `:stroke-style`). Don't normalize tags, attribute values or outer defs IDs as though they were attribute keys; defs IDs can decode as keywords or strings. Normalize when constructing the shape; legacy data goes through the SVG repair in `app.common.files.migrations`, not the binfile cleaner.

## Cross-module update checklist

When changing the file data model, check the relevant paths:
- Schema/type definitions under `common/src/app/common/types*` and helpers under `common/src/app/common/files*` / `logic*`.
- File migrations in `common/src/app/common/files/migrations.cljc` when old files cannot safely use absence/default behavior.
- Frontend edit forms under `frontend/src/app/main/ui/workspace/sidebar/options/`; multi-selection behavior is usually in `shapes/multiple.cljs` and must handle `:multiple` values.
- SVG/file render and export metadata under `frontend/src/app/main/ui/shapes/*`, especially `export.cljs` when an attr is not a native SVG property.
- SVG import starts at `frontend/src/app/main/data/workspace/media.cljs` and shape construction at `common/src/app/common/files/shapes_builder.cljc`; attrs not exported and imported will be lost on reimport.
- Viewer inspect and code generation under `frontend/src/app/main/ui/inspect/*` and `frontend/src/app/util/code_gen.cljs` / markup/style helpers when handoff output should expose the attr.
- Exporter/library consumers when the change affects file construction, rendering, or packaged `.penpot` archives.

## Migrations

If an absent attribute cannot preserve old behavior, see `mem:common/file-migrations` for migration and ordering rules.

Model changes can also require file feature flags or migration metadata updates; check nearby migrations and `common/src/app/common/features.cljc` before inventing a new pattern.
