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

## Batches that were already built

`common/src/app/common/files/changes_merge.cljc` (alias `mch`) rewrites a finished batch into an equivalent smaller one, without touching file data.
- `mch/merge-reg-objects`: the component sync emits one `:reg-objects` per touched shape, so a component swap carries hundreds of them for a handful of groups. It collapses them into one per container, union of `:shapes`, moved to the end of the batch so a group is always recomputed after the shapes that resized it. Applied to both redo and undo, on every commit (`frontend/src/app/main/data/changes.cljs`) and again within each buffered commit before queueing (`frontend/src/app/main/data/persistence.cljs`). Keep commits separate: the backend can reject them by `commit-id`, so persistence must not fuse changes across commit boundaries. Measured on a real swap: 291 `:reg-objects` become 36, the whole batch goes 806 changes / 6.4 MB to 551 / 324 KB.

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

- Structural add/remove and change-parent inverses restore the copy parent's
  `:remote-synced` as well as `:touched`. Removing or adding a child clears
  the former even when touched groups do not change; restoring only the
  groups loses prior state on undo (F157).

- Undo of bulk removals moves restored siblings in original sibling order.
  Component sync records each removal's current index from builder objects;
  indexes in the original container become stale after earlier removals.
  When the builder lacks a main's container (inverse sync), use that main
  container's original index. Both rules preserve order after several
  siblings disappear (F158).

- A move inverse can restore component nesting that existed before the
  move, even when the normal component-loop check would reject creating
  it now (F177). `change-parent` records `:restore-component-nesting` on
  the inverse only when the original position already repeats a component.
  The change applier still rejects object-tree cycles and copy-structure
  edits. Normal forward moves keep the component-loop guard.

Normal duplication checks the component nesting guard too (F181). A finite
repeated component tree can use a swapped child to cut recursion; adding
another child in its main can open an expanding branch. cld/allow-duplicate?
combines copy-structure and nesting guards for normal frontend/pure nodes.
The duplicate builder checks the actual destination after ALT placement;
a duplicate at the page root remains allowed. Undo still restores existing
nesting through its explicit inverse flag, not through a forward bypass.

Component restoration reports its component id from components-changed
(F183). Its main does not exist in the old page objects, so add/mod-object
detection cannot find it. Restore can rename a variant when its container
has gone; the restored component and its dependents must sync in that same
undo group.
