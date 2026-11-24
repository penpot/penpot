# Common Architecture and Workflow

`common/` intro: shared CLJC for frontend, backend, exporter, library/file tooling, tests. Small semantic changes can affect multiple runtimes.

## Layering and cross-runtime rules

Use reader conditionals for platform-specific code. Because CLJC runs on JVM and CLJS targets, avoid assuming browser-only or JVM-only behavior unless the reader conditional isolates it.

Respect the intended abstraction direction in new/refactored code:
- generic data utilities should not know Penpot domain concepts;
- `types.*` should preserve invariants for a single domain entity or ADT;
- `files.*` can coordinate several entities inside a file and preserve referential integrity;
- `changes*` should adapt serializable change records to lower-level operations and avoid embedding broad business algorithms;
- `logic.*` and frontend/backend event layers own higher workflow/business behavior.

Some legacy code violates this layering; do not copy those violations into new code when a focused refactor is practical.

## Build-mode dependent laziness

`app.common.schema/check-fn` resolves schemas through `app.common.data.macros/lazy-delay`: an invalid schema can fail on namespace load in development and JVM tests, but at first use in production CLJS. See the macro docstring and each target's `:closure-defines` for the evaluation rules.

## Focused memory routing

- URL construction on either runtime: `mem:common/uri` for relative-path resolution and preserving a deployment subpath.

Model, schema, and persistence shape:
- File/page/shape/component attr changes, import/export surfaces, inspector/codegen, and cross-module checklist: `mem:common/data-model-change-checklist`.
- Token data structures, token import/export, active theme/set semantics, and schema/coercion behavior: `mem:common/tokens-schema-subtleties`.
- For organization and team permission rules shared across runtimes, start at `app.common.types.organization`.

Geometry and layout:
- Shape geometry invariants, redundant geometry fields, and geometry-sensitive tests: `mem:common/geometry-invariants`.
- Coordinate drift and approximate float comparisons: `mem:common/decimals-and-coordinates`.
- Layout/grid assignment, deassignment, metadata cleanup, and auto-positioning: `mem:common/layout-grid-subtleties`.

Change pipeline, validation, and migrations:
- Change records, undo/redo architecture, changes-builder API, and production-path mutation guidance: `mem:common/changes-architecture`.
- Change application, shape-tree edits, validation/repair, and second-pass touched behavior: `mem:common/file-change-validation-migration-subtleties`.
- Adding, renumbering or replacing a file migration: `mem:common/file-migrations`.

Components, variants, and debugging:
- Component/variant data model, ref chains, touched override semantics, and cloning paths: `mem:common/component-data-model`.
- Component swap, variant switch, and keep-touched pipeline: `mem:common/component-swap-pipeline`.
- Live inspection snippets, temporary runtime patching, and test-side debugging helpers for common change/component behavior: `mem:common/component-debugging-recipes`.

Text and tests:
- Shared text data conversion, DraftJS compatibility, modern text content, and derived position data: `mem:common/text-subtleties`.
- Common test commands, helper conventions, production-path test mutations, and runtime coverage choices: `mem:common/testing`.
- Cross-cutting testing principles, anti-patterns, and verification checklist: `mem:testing`.
