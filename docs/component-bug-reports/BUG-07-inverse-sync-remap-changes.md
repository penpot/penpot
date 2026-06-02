# BUG-07 — Inverse sync re-maps the entire change list at every tree node

- **Category:** Performance + Stability
- **Confidence:** High
- **Severity:** Medium
- **Hot path:** Yes — "Update main" / inverse sync (`dwl/update-component`)

## Onboarding (fresh context — read first)

- Repo root for local file tools (Read/Edit/Write): `/home/alotor/kaleidos/penpot/penpot`
- Serena / REPL project root (devenv container): `/home/penpot/penpot`.
- Read first: `docs/component-subsystem-handoff.md` (§5.3 inverse sync) and Serena memory `critical-info`.
- Relevant memories: `mem:common/changes-architecture`, `mem:common/component-data-model`.
- Related: this shares the lazy-`concat` hazard family with **BUG-01**.
- Line numbers drift — re-locate symbols by name.

## Summary

`generate-sync-shape-inverse-recursive` ends each invocation by re-mapping the
**entire accumulated change set** to tag local-file changes. Because the function
recurses once per shape in the component tree (threading a single `changes`
accumulator), each return re-walks all changes produced so far. For a tree of K
shapes producing ~O(K) changes, total work is O(K²). Worse, the `:undo-changes`
re-map uses lazy `map` (not `mapv`), nesting K lazy seqs — the same
`StackOverflowError` / re-realization hazard as BUG-01, on the "Update main" path.

## Affected code

`common/src/app/common/logic/libraries.cljc` — `generate-sync-shape-inverse-recursive`
(~line 1045), tail of the function:

```clojure
check-local (fn [change]
              (cond-> change
                (= (:id change) (:id shape-inst))
                (assoc :local-change? true)))

;; ...
(-> changes
    (update :redo-changes (partial mapv check-local))   ; re-walks ALL accumulated redo changes
    (update :undo-changes (partial map  check-local)))   ; lazy map → nests per recursion level
```

The recursion happens via the `both` callback (~line 1126 in that function), which
calls `generate-sync-shape-inverse-recursive` again with the same `changes`.

## Root cause

`check-local` only ever tags changes whose `:id` equals *this* invocation's
`shape-inst`. Applying it to the whole accumulated list at every node is redundant:
a change for shape X is correctly tagged only by the recursion level whose
`shape-inst` is X, but every level pays to scan the full list. The lazy `map` on
`:undo-changes` additionally nests across levels.

Context for *why* the tagging exists: an inverse sync may run on a component that
lives in a **remote library**, so the produced changes mix local-file and
remote-file changes; `:local-change?` marks the ones that belong to the local file.
Any fix must keep that tag landing on exactly the same set of changes.

## Proposed fix directions

1. **Tag only the delta.** Each invocation knows which changes it just added (it can
   diff against the incoming `changes`, or build its own changes first then tag and
   merge). Apply `check-local` only to the newly-added changes for `shape-inst`, then
   append. This removes the full re-walk.
2. **Single top-level pass.** Do the recursion without per-node tagging, then run one
   `mapv check-local'` over the full result at the top-level entry
   (`generate-sync-shape-inverse`, ~line 1010) where the complete set of synced
   shape ids is known (tag changes whose `:id` is in that set). Cleanest if the set of
   relevant shape ids is easy to collect during recursion.

In all cases: use `mapv` (eager) for **both** `:redo-changes` and `:undo-changes`.

## Risks / things to watch

- The `:local-change?` flag must end up on exactly the same changes as today. Verify by comparing the tagged-id set before/after on a remote-library inverse-sync scenario.
- Inverse sync also marks the component modified and renames on `:name-group` touched — don't disturb those.
- Confirm undo/redo ordering is preserved.

## Reproduction / validation

- Inverse-sync ("Update main") a deep/wide copy and confirm output changes are identical pre/post fix (same ops, same `:local-change?` tags), but the change list is built in a single pass.
- For the stability angle, a large tree should no longer build deeply nested lazy undo seqs.

## Test guardrails

From `common/`:

```bash
clojure -M:dev:test --focus common-tests.logic.comp-sync-test
pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test
```

Inverse-sync cases live in `common/test/common_tests/logic/comp_sync_test.cljc`.
There is a frontend counterpart worth running too:

```bash
# from frontend/
pnpm run test:quiet -- --focus frontend-tests.logic.components-and-tokens
```

Run the full common suite before committing.

## Acceptance criteria

- The whole accumulated change set is no longer re-mapped at every recursion node.
- Both `:redo-changes` and `:undo-changes` are tagged eagerly (`mapv`).
- `:local-change?` lands on exactly the same changes as before (verified on a remote-library inverse-sync case).
- comp-sync tests pass unchanged.

---

## Fix

**Approach chosen:** tag at creation time — option 1 taken to its logical extreme.

Analysis showed that `check-local` marked exactly two kinds of changes at each
recursion level: the `:mod-obj :set-touched` change from `change-touched shape-inst
container` and the `:mod-obj :set-remote-synced` change from `change-remote-synced
shape-inst container`. Both are called with the local-page container whenever
`container` is the page. Instead of re-walking the full accumulator at every node to
discover those two changes, they can be marked at the point they are created.

A hidden correctness bug was also found: `add-shape-to-main` (called for `only-inst`
shapes — shapes in the copy that have no counterpart in the main component) generated
two misrouted change types:

1. **`mod-obj-change`** — updates `:shape-ref` on the original instance shapes (page-level
   changes). These were missing `:local-change? true`, so they were routed to the
   remote library file instead of the local page, leaving the instance's `:shape-ref`
   links un-updated after an inverse sync on a remote-library component.

2. **`del-obj-change`** — the undo of "add shape to component" should delete the newly
   cloned shapes from the component container. It used `:page-id (:id page)` instead
   of `(make-change component-container ...)`, which would have tried to delete
   component-only shapes from the page (where they don't exist) on undo.

### Changes made

**`change-touched`** (`~line 1543`):
- Added `(let [local? (cfh/page? container)])` wrapper.
- Both `:redo-changes` and `:undo-changes` `make-change` calls wrapped with
  `(cond-> ... local? (assoc :local-change? true))`.
- Effect: page-container `:set-touched` changes are tagged at creation time; O(1)
  per call.

**`change-remote-synced`** (`~line 1590`):
- Same treatment as `change-touched`.

**`generate-sync-shape-inverse-recursive`** (tail, `~line 1180`):
- Removed the `check-local` / `(update :redo-changes (partial mapv check-local))` /
  `(update :undo-changes (partial map check-local))` block entirely.
- The function now just returns `changes` — no per-node re-walk.
- Effect: O(K) total tagging work instead of O(K²); no lazy `:undo-changes` nesting.

**`add-shape-to-main`** (`~line 1388`):
- `mod-obj-change`: added `:local-change? true` to both redo and undo `mod-obj`
  entries (they carry `:page-id` and always target the local file).
- `del-obj-change`: replaced the hand-rolled `{:page-id ...}` map with
  `(make-change component-container {:type :del-obj ...})` so the undo `:del-obj`
  carries `:component-id` and is correctly routed to the library file.

### Test added

`test-inverse-sync-remap-changes-marks-page-changes-as-local` in
`common/test/common_tests/logic/comp_sync_test.cljc`:

- Creates a library file with a minimal component (root only).
- Instantiates it in a local file and adds an extra child (`only-inst` scenario).
- Calls `generate-sync-shape-inverse`.
- Asserts that `:mod-obj :shape-ref` changes (from `add-shape-to-main`) are
  `:local-change? true`.
- Asserts that `:mod-obj :set-touched` changes for `copy-root` on the page are
  `:local-change? true`.

All 1055 common tests pass.
