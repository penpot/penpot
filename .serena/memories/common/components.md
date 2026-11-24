# Components, variants and file integrity

Start at `app.common.types.component`, `app.common.types.container`, `app.common.logic.libraries` and `app.common.logic.variants`. Referential validation and repair live in `app.common.files.validate` and `app.common.files.repair`.

## Model and synchronization

- A shape can simultaneously be a main instance, a copy or an instance root. Main instances carry `:main-instance` and `:component-id`; copies carry `:shape-ref`; roots carry `:component-root` and component identity. Nested instances make these roles overlap.
- `:shape-ref` chains can cross files. `find-ref-shape` and `get-ref-chain-until-target-ref` follow them; the nearest component head and the outermost instance root are not interchangeable.
- `:touched` stores override groups from `sync-attrs`. `set-shape-attr` marks copies through `set-touched-group`, subject to ignore flags; `:position-data` is derived and never touched. Geometry and content-path comparisons use approximate equality, but width and height are excluded from the `is-geometry?` branch.
- Main shapes can acquire touched flags through cloning. `add-touched-from-ref-chain` can propagate upstream flags during variant switch, so a locally untouched shape can still act touched. `make-component-instance` cleans attrs including `:touched`, `:variant-id` and `:variant-name`; duplicating a component master does not have identical clean-copy semantics. Identify the creation path before diagnosing touched.
- Swap slots are touched groups `swap-slot-<uuid>` on swapped copy sub-heads. Sync matches those by slot; other children match by `shape-ref`, not index. Child order converges through async sync. `:mov-objects` and `:reorder-children` guard copy structure unless a sync or swap sets `allow-altering-copies`; new structural changes need the same guard.
- Deleting inside a component main propagates to referencing copies transitively across pages to prevent dangling refs. `generate-delete-shapes` skips propagation for the main root itself and for `allow-altering-copies` flows. Adding a shape to a copy parent clears its `:remote-synced` unless `ignore-touched`.

## Variants and swap

- A variant container is a frame with `:is-variant-container`; its variant masters refer to it via `:variant-id` and have `:variant-name`. Component rows carry `:variant-properties`. `is-variant?` applies to rows and shapes. Moving a shape into a variant container can convert it into a variant and duplicate the component, not merely reparent it.
- Frontend `component-swap` re-reads the shape from live page objects: sibling swaps or layout can move it, and a missing shape is a no-op. Variant switch uses `keep-touched?` for eligible overrides; `component-multi-swap` intentionally passes false.
- `generate-component-swap` makes the replacement; `generate-keep-touched` walks old children, incorporates ref-chain touched groups and finds corresponding target shapes. `update-attrs-on-switch` compares the old copy, fresh target and origin master. Preserve guards for differing masters, shape types, auto text and composite geometry, including fixed-layout and path cases; do not assume roots have identical positions.

## Referential integrity and repair

- Referential and semantic validation in `app.common.files.validate` runs for files with `"components/v2"`; it visits the root, orphan shapes and component records. `validate-file!` raises `:validation` / `:referential-integrity` with details. `update-file` uses change-scoped validation of affected pages and components; schema validation is separate in `mem:common/change-application`.
- `repair-file` produces redo changes that callers must apply or persist. `repair-file-until-valid` stops on no errors, unchanged data, a stable error fingerprint without `:shape`, or its iteration limit. Check the fingerprint before another pass; backend `PointerMap` equality is pointer identity. Multiple `:set` repairs on one shape can need multiple passes because each repair starts from the original file data.
- For `:ref-shape-not-found`, repair can restore a local component's `:main-instance-id` when the recorded main is missing but its matching main exists on the recorded page. Otherwise it tries a near-main reassignment or detaches the copy subtree.
- `:missing-slot` is a real swap only when the copy sub-head's `:shape-ref` is no longer a child of the near main parent. A sibling at a different position is a reorder, not a swap; assigning a slot blocks normal sync. `find-near-match` is a repair heuristic, not a positional validity rule. Migration 0019 uses the same membership rule; see `mem:common/file-migrations`.

## Diagnosis and tests

- For production-path swap tests use `app.common.test-helpers.compositions/swap-component-in-shape` with `:keep-touched?`; see `common/test/common_tests/logic/variants_switch_test.cljc` and `mem:common/testing`. `thf/dump-file` prints shape trees. Use production change helpers for fixture mutations.
- To inspect recent changes in a live workspace, examine `[:workspace-undo :items]`, including each item's `:tags`, `:redo-changes` and `:operations` for `:mod-obj`. See `mem:frontend/cljs-repl` for runtime selection and temporary instrumentation. Restore patched vars and remove source traces after debugging.
- To trace `update-attrs-on-switch` during a UI swap, save the original var, wrap it to capture current, previous and origin shapes, then restore it:

```clojure
(def orig (deref #'app.common.logic.libraries/update-attrs-on-switch))
(def trace-buf (atom []))
(set! app.common.logic.libraries/update-attrs-on-switch
      (fn [& args]
        (swap! trace-buf conj
               (let [[_ curr prev _ _ origin _] args]
                 {:curr (select-keys curr [:name :x :y :selrect :points :touched])
                  :prev (select-keys prev [:name :x :y :selrect :points :touched])
                  :origin-ref (select-keys origin [:id :name :x :y :width :height :selrect])}))
        (apply orig args)))
;; Trigger the UI action and inspect @trace-buf.
(set! app.common.logic.libraries/update-attrs-on-switch orig)
```
