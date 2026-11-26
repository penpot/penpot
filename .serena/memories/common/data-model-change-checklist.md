# Common Data Model Change Checklist

## Attribute conventions

- Prefer optional page/shape attrs with default behavior when absent. Reverting to default should usually remove the attr instead of storing nil.
- Do not design nil as a distinct persisted state from absence: import/export and cleanup paths may filter nil attrs away. Change records can nevertheless distinguish omission from explicit nil; see `mem:common/changes` for `:mod-component`.
- Avoid Clojure-special naming in exported object attrs, especially boolean names ending in `?`; exported/imported data must survive JSON/SVG/Transit and external tooling.
- Normal shape attrs that participate in component sync belong in `app.common.types.component/sync-attrs` with the correct touched group. `:applied-tokens` participates through a separate route, not `sync-attrs`. See `mem:common/components` for synchronization and touched semantics.
- Shape `:svg-attrs` keys are kebab-case (`fill-rule`, not `fillRule`): birth (`app.common.svg/attrs->kebab-props`, applied everywhere data lands on a shape) normalizes any outside spelling through the `svg-props` whitelist, and render boundaries (`get-svg-props`, `render-xml*`, `svg-node*`) translate back to camelCase for React. `:svg-defs` node attrs follow the same rule recursively; svg-raw `:content` attrs are kebab-ized spelling-only, never filtered — the sidebar reads `:stroke-style`, which is outside the whitelist. Non-spec attrs Penpot keeps live in `penpot-extra-attrs` (seeded with `:stroke-style`) and travel in source spelling. Excluded, each with its reason: `:tag` values (React and tag sets need them verbatim; the schema decoder restores keywords from stored strings), outer `:svg-defs` keys (id strings at birth, keywords or strings after JSON decode — accept both), all values (the kebab reader never touches values). Repair sites: birth plus `app.common.svg/normalize-shape`, shared by migration `0037-fix-shape-svg-attrs-and-defs` (replaces the withdrawn `0021` and `0035`) and `paste-shapes` (clipboard data skips migrations, so shapes copied from older versions are normalized on paste); the binfile cleaner carries no svg repair.

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
