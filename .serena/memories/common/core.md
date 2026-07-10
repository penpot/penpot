# Common Architecture and Workflow

`common/` intro: shared CLJC for frontend, backend, exporter, library/file tooling, tests. Small semantic changes can affect multiple runtimes.

## Stable namespace map

- `app.common.data` and `app.common.data.macros`: generic data helpers and performance macros that do not depend on Penpot domain entities.
- `app.common.types.*`: shared shape/file/page/component/token data types, schemas, predicates, and entity-local operations. `app.common.types.nitrate-permissions` contains shared fail-closed Nitrate organization/team permission rules.
- `app.common.files.*`: file-level operations, shape tree helpers, change application, migrations, validation, and undo/redo-related logic.
- `app.common.logic.*`: higher-level workflows/algorithms over files, shapes, components, variants, libraries, tokens, etc.
- `app.common.geom.*`: geometry helpers and transformations.
- `app.common.schema` / `app.common.schema.*`: Malli abstraction layer.
- `app.common.math`, `app.common.time`, `app.common.uuid`, `app.common.json`, etc.: cross-runtime utilities.
- `app.common.test_helpers.*`: test builders and production-path helpers.

## Layering and cross-runtime rules

Use reader conditionals for platform-specific code. Because CLJC runs on JVM and CLJS targets, avoid assuming browser-only or JVM-only behavior unless the reader conditional isolates it.

Respect the intended abstraction direction in new/refactored code:
- generic data utilities should not know Penpot domain concepts;
- `types.*` should preserve invariants for a single domain entity or ADT;
- `files.*` can coordinate several entities inside a file and preserve referential integrity;
- `changes*` should adapt serializable change records to lower-level operations and avoid embedding broad business algorithms;
- `logic.*` and frontend/backend event layers own higher workflow/business behavior.

Some legacy code violates this layering; do not copy those violations into new code when a focused refactor is practical.

## Focused memory routing

Model, schema, and persistence shape:
- File/page/shape/component attr changes, import/export surfaces, inspector/codegen, and cross-module checklist: `mem:common/data-model-change-checklist`.
- Token data structures, token import/export, active theme/set semantics, and schema/coercion behavior: `mem:common/tokens-schema-subtleties`.

Geometry and layout:
- Shape geometry invariants, redundant geometry fields, and geometry-sensitive tests: `mem:common/geometry-invariants`.
- Coordinate drift and approximate float comparisons: `mem:common/decimals-and-coordinates`.
- Layout/grid assignment, deassignment, metadata cleanup, and auto-positioning: `mem:common/layout-grid-subtleties`.

Change pipeline, validation, and migrations:
- Change records, undo/redo architecture, changes-builder API, and production-path mutation guidance: `mem:common/changes-architecture`.
- Change application, shape-tree edits, validation/repair, migrations, and second-pass touched behavior: `mem:common/file-change-validation-migration-subtleties`.

Components, variants, and debugging:
- Component/variant data model, ref chains, touched override semantics, and cloning paths: `mem:common/component-data-model`.
- Component swap, variant switch, and keep-touched pipeline: `mem:common/component-swap-pipeline`.
- Live inspection snippets, temporary runtime patching, and test-side debugging helpers for common change/component behavior: `mem:common/component-debugging-recipes`.

Text and tests:
- Shared text data conversion, DraftJS compatibility, modern text content, and derived position data: `mem:common/text-subtleties`.
- Common test commands, helper conventions, production-path test mutations, and runtime coverage choices: `mem:common/testing`.
- Cross-cutting testing principles, anti-patterns, and verification checklist: `mem:common/testing-principles`.

## Areas without focused memories

Common areas with little or no dedicated memory include colors, media/SVG helpers, path operations, thumbnail helpers, generic pools, weak refs, and some utility namespaces. Treat work there as source/test-led unless a focused memory exists.
