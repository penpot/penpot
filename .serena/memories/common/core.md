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

## Focused memory routing

- URL construction on either runtime: `mem:common/uri` for relative-path resolution and preserving a deployment subpath.

Model, schema, and persistence shape:
- File/page/shape/component attr changes, import/export surfaces, inspector/codegen, and cross-module checklist: `mem:common/data-model-change-checklist`.
- Design token model, import/export and workspace application: `mem:common/tokens`.
- Shared schema coercion, validators and build-mode resolution: `mem:common/schema`.
- For organization and team permission rules shared across runtimes, start at `app.common.types.organization`.

Geometry and layout:
- Shape geometry invariants, coordinate drift and approximate comparisons: `mem:common/geometry`.
- Layout/grid assignment, deassignment, metadata cleanup, and auto-positioning: `mem:common/layout`.

Change pipeline, validation, and migrations:
- Change construction, undo/redo, application, compaction and schema validation: `mem:common/changes`.
- Adding, renumbering or replacing a file migration: `mem:common/file-migrations`.

Components, variants, and debugging:
- Components, variants, referential integrity, repair and diagnosis: `mem:common/components`.

Text and tests:
- Shared text data conversion, DraftJS compatibility, modern text content, and derived position data: `mem:common/text-subtleties`.
- Common test commands, helper conventions, production-path test mutations, and runtime coverage choices: `mem:common/testing`.
- Cross-cutting testing principles, anti-patterns, and verification checklist: `mem:testing`.
