# Common Data Model Change Checklist

## Attribute conventions

- Prefer optional page/shape attrs with default behavior when absent. Reverting to default should usually remove the attr instead of storing nil.
- Do not treat nil as a distinct persisted state from absence. Import/export and cleanup paths may filter nil attrs away.
- Avoid Clojure-special naming in exported object attrs, especially boolean names ending in `?`; exported/imported data must survive JSON/SVG/Transit and external tooling.
- Any new shape attr that participates in component sync must be listed in `app.common.types.component/sync-attrs` with the correct touched group. Attrs absent from `sync-attrs` are ignored by component synchronization.

## Cross-module update checklist

When changing the file data model, check the relevant paths:
- Schema/type definitions under `common/src/app/common/types*` and helpers under `common/src/app/common/files*` / `logic*`.
- File migrations in `common/src/app/common/files/migrations.cljc` when old files cannot safely use absence/default behavior.
- Frontend edit forms under `frontend/src/app/main/ui/workspace/sidebar/options/`; multi-selection behavior is usually in `multiple.cljs` and must handle `:multiple` values.
- SVG/file render and export metadata under `frontend/src/app/main/ui/shapes/*`, especially `export.cljs` when an attr is not a native SVG property.
- SVG import/parser paths under `frontend/src/app/worker/import/parser.cljs`; attrs not exported and imported will be lost on reimport.
- Viewer inspect and code generation under `frontend/src/app/main/ui/viewer/inspect/*` and `frontend/src/app/util/code_gen.cljs` / markup/style helpers when handoff output should expose the attr.
- Exporter/library consumers when the change affects file construction, rendering, or packaged `.penpot` archives.

## Migrations

Existing files should keep working unchanged when possible. If absence cannot preserve old behavior, add a migration and preserve append/order semantics described in `mem:common/file-change-validation-migration-subtleties`.

Model changes can also require file feature flags or migration metadata updates; check nearby migrations and `common/src/app/common/features.cljc` before inventing a new pattern.
