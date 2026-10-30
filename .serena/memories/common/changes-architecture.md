# File Mutations: Changes and Undo Architecture

Penpot mutates file data through change records. A change set is both the persistence payload and the basis for undo/redo, so UI actions, tests, backend file updates, and library/file tooling should drive the production change pipeline instead of ad hoc object-map mutation.

## Change shape

Each change is a map such as `{:type ... :id ... :page-id ...}`. Common families:

- `:add-obj`, `:mod-obj`, `:del-obj`: shape lifecycle. `:mod-obj` contains `:operations`, commonly `{:type :set :attr ... :val ... :ignore-geometry ... :ignore-touched ...}` or `{:type :set-touched ...}`.
- `:add-component`, `:mod-component`, `:del-component`: component/library metadata.
- Shapes stored inside the component record (deleted components keep them in `:objects`) are never written as a whole map: `mod-component` carries only component metadata by default, and shapes travel as `:mod-obj`/`:add-obj`/`:del-obj` changes scoped with `:component-id`. `pcb/update-component` has an `:objects? true` option for the two repairs that move or clear that key, and `ctkl/mod-component` reads an absent `:objects` as "leave the stored shapes alone" (only an explicit `nil` drops them). Before this, one component swap resent the full object map of every deleted component it touched (~6 MB in the request); do not reintroduce it.
- `:add-children`, `:remove-children`, `:reg-objects`: tree and object-map edits.
- `:set-option`, `:add-page`, `:mov-page`, and related file/page metadata changes.

Each transaction carries `:redo-changes` and inverse `:undo-changes`. The undo stack stores transactions and can move its index backward/forward.

## changes-builder API

`common/src/app/common/files/changes_builder.cljc` (usually alias `pcb`) is the fluent builder. Start from `(pcb/empty-changes <it> <page-id>)` or `(pcb/empty-changes nil <page-id>)` for tests.

High-value builder operations:
- `pcb/with-page-id`, `pcb/with-objects`, `pcb/with-library-data`: set context for following operations.
- `pcb/update-shapes ids update-fn`: emits `:mod-obj` with diff-derived `:set` ops. Options include `{:with-objects? true}`, `{:ignore-touched true}`, and `{:attrs #{...}}`.
- `pcb/add-objects`, `pcb/change-parent`, `pcb/remove-objects`, `pcb/resize-parents`: shape/tree edits.
- `pcb/add-component`, `pcb/update-component`, `pcb/mod-component`: component/library edits. Repairs that fix shapes inside a component use `pcb/with-container` + `pcb/with-objects` + `pcb/update-shapes` so the changes come out scoped with `:component-id`.
- `pcb/set-translation? true`: marks the whole change set as translation-only, which lets component sync skip expensive work.

## Applying changes in tests

`thf/apply-changes` in `app.common.test-helpers.files` is the test analog of the production applier. It validates by default; pass `:validate? false` only for intentionally-invalid intermediate states.

The applier uses the same `process-operation` multimethod as production (`common/src/app/common/files/changes.cljc`), so tests that use it exercise production behavior.

## :touched and geometry

For component touched semantics and sync groups, read `mem:common/component-data-model`. For the exact `set-shape-attr` / second-pass behavior during change application, read `mem:common/file-change-validation-migration-subtleties`. For transform-specific ignore-geometry behavior, read `mem:frontend/workspace-transform-subtleties`.

## Inspection

To inspect what a UI action emitted, use `mem:frontend/cljs-repl` with the snippets in `mem:common/component-debugging-recipes` rather than adding temporary source instrumentation.