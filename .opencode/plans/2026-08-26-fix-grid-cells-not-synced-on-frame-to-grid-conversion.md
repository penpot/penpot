# Plan: Fix grid cells not synced when converting a component frame to grid/flex

## Context

When a component's main frame `A` (children `B C D`) is converted to a layout
(grid or flex), the editor reorders `A`'s children (e.g. to `D C B`). Those
changes propagate to a copy `A'`, whose children become `D' C' B'`. A second
relayout then fires on `A'` and reorders its children back to `B' C' D'` — that
last jump is wrong: the copy's children are already correct and the relayout
should not fire.

Root cause is in the component sync engine (`common/`), not the frontend
reflow. Two defects combine:

1. **Gating asymmetry.** In `generate-sync-shape-direct-recursive`
   (`common/src/app/common/logic/libraries.cljc`), the flex branch is gated on
   the *main* shape (`(ctsl/flex-layout? shape-main)`, line 888) but the grid
   branch is gated on the *copy* shape (`(ctsl/grid-layout? shape-inst)`,
   line 1003). When a frame is converted to grid, the copy `shape-inst` is still
   a plain frame at sync time, so `update-grid-copy-attrs` never runs and the
   copy ends up with `:layout :grid` + tracks but **empty `:layout-grid-cells`**.

2. **`merge-cells` does not propagate structure.** Even when it runs,
   `update-grid-copy-attrs` (line 2439) merges the main's cells with
   `ctsl/merge-cells` (`common/src/app/common/types/shape/layout.cljc` line
   1694). With `omit-touched?=true` it keeps the copy's cell positions
   (`:row`/`:column`/`:row-span`/`:column-span`) and the copy's cell set (it
   only patches `:shapes`/`:position`/`:area-name` for cells sharing a UUID;
   new/removed cells do not propagate). For a freshly converted frame the copy
   has no cells, so the result is empty and the cells are re-derived from stale
   positions — producing an order different from the main.

The consequence is that the copy's `:layout-grid-cells` never reflect the main's
cell assignment. The frontend `sync-file-frontend-events`
(`frontend/src/app/main/data/workspace/libraries.cljs` line 1152) then emits
`:layout/update` for the copy, the reflow repositions the copy's children from
stale/empty cells, and the visual order snaps back — the spurious relayout.

## Affected Modules

- `common/` — sync engine and grid layout data model. See `mem:common/core`,
  `mem:common/component-data-model`, `mem:common/layout-grid-subtleties`,
  `mem:common/changes-architecture`, `mem:common/testing`.
- `frontend/` (context only) — `sync-file-frontend-events` emitting
  `:layout/update`; no change expected unless the fix reveals a frontend
  assumption. See `mem:frontend/core`.

Key invariant from `mem:common/layout-grid-subtleties`: copy child order is
owned by the component sync engine; `:reorder-children`/`:mov-objects` refuse to
alter copies unless `allow-altering-copies`. The grid cell sync must therefore
make the copy's cells consistent with the copy's (already-synced) `:shapes`
order.

## Architecture Decisions

- **Propagate the main's cells wholesale when the copy has no grid override.**
  The copy's grid override is already tracked per-shape by the `:layout-grid-cells`
  touched group (`ctk/touched-group?`). When that group is not touched, the
  correct behavior is to copy the main's `:layout-grid-cells` after remapping
  shape ids (`ctsl/remap-grid-cells`), preserving cell ids/positions/assignments.
  Only when the group is touched do we preserve the copy's cells (current merge
  behavior).

- **Align the grid gating with the flex gating** by checking `shape-main`
  instead of `shape-inst`. This is the minimal, symmetric fix and matches the
  flex branch.

- **Keep the fix in `common/`.** No change to the frontend reflow path is
  needed; the reflow is correct once the copy's cells are correct.

## Risks & Considerations

- **Touched overrides must be preserved.** A user may manually move a copy child
  to a different cell (`:layout-grid-cells` touched). The fix must keep the
  existing merge path for that case, otherwise user overrides in copies would be
  wiped on every sync.
- **Reset sync (`omit-touched?=false`).** Today `merge-cells` returns the copy's
  cells unchanged for reset (no merge), which is also wrong for reset-to-main.
  The wholesale branch improves reset too (touched is cleared before the cell
  sync, so the group is not touched and main's cells are taken).
- **Remote/nested components.** `update-grid-copy-attrs` remaps via
  `shape-ref`/swap-slot lookup; the wholesale path must reuse the same `ids-map`
  so nested instances and swap slots keep working.
- **Cell id identity.** Cell ids are `uuid/next`; main and copy cell ids only
  match if the copy was instantiated from a grid main. Comparing by cell id is
  not reliable; the fix must compare/assign by the remapped shape ids and
  positions, not by cell uuid equality.

## Approach

Follow TDD (Red → Green → Refactor) with the Prove-It pattern: first write a
reproduction test that fails, then fix, then confirm green.

### Step 1 — RED: reproduction test (this step)

Add a test to `common/test/common_tests/logic/comp_sync_test.cljc` that:

1. Builds a component with a frame main + three rect children at distinct `x`
   positions, plus a copy (using `tho/add-component-with-many-children-and-copy`).
2. Converts the main frame to a grid via the production-path update helper
   `cls/generate-update-shapes` with `:with-objects? true`, applying the same
   transformation as the frontend `get-layout-initializer`:
   merge grid layout attrs + `grid/calculate-params` + `ctl/assign-cells` +
   `ctl/reorder-grid-children`.
3. Runs `cll/generate-sync-file-changes` and `thf/apply-changes`.
4. Asserts that the copy is a grid, that its `:layout-grid-cells` has the same
   cell structure as the main's (remapped to copy ids), and that its `:shapes`
   order matches the main's (mapped through `:shape-ref`).

The test fails today because the copy's `:layout-grid-cells` is empty.

### Step 2 — GREEN: fix the gating

In `common/src/app/common/logic/libraries.cljc`, change line 1003 from
`(ctsl/grid-layout? shape-inst)` to `(ctsl/grid-layout? shape-main)`.

### Step 3 — GREEN: propagate main cells when the copy is not touched

In `update-grid-copy-attrs` (`common/src/app/common/logic/libraries.cljc` line
2439), branch on `(ctk/touched-group? shape-copy :layout-grid-cells)`:

- not touched → `(assoc :layout-grid-cells (ctsl/remap-grid-cells shape-main ids-map) :layout-grid-cells)`
  (take the main's cells wholesale, remapped), then `(ctsl/assign-cells objects)`.
- touched → keep the current `merge-cells` behavior.

This also fixes the reset path.

### Step 4 — GREEN: flex coverage (verification)

Confirm `update-flex-child-copy-attrs` (line 2378) already propagates child flex
attrs on frame→flex conversion (its gating already uses `shape-main`). Add a
sibling test if it is missing coverage; no code change expected unless the test
surfaces a symmetric defect.

### Step 5 — REFACTOR + full verification

Run the full common suite and lint/format checks (see Testing Strategy).

## Task List

### Phase 1: Reproduction (RED)
- [ ] Task 1: Add `test-sync-grid-cells-when-converting-main-to-grid` to
      `common/test/common_tests/logic/comp_sync_test.cljc`.

**Acceptance criteria:**
- [ ] Test builds a component+copy, converts main to grid, syncs, and asserts the
      copy's `:layout-grid-cells` (remapped) and `:shapes` order match the main.
- [ ] Test FAILS when run (copy's cells empty / mismatch).

**Verification:**
- [ ] `clojure -M:dev:test --focus common-tests.logic.comp-sync-test/test-sync-grid-cells-when-converting-main-to-grid` → RED (output to file, then read).

**Dependencies:** None

### Checkpoint: Phase 1
- [ ] Reproduction test is RED, proving the bug.

### Phase 2: Fix (GREEN)
- [ ] Task 2: Fix grid gating `shape-inst` → `shape-main` (libraries.cljc:1003).
- [ ] Task 3: `update-grid-copy-attrs` takes main's cells wholesale when the copy's
      `:layout-grid-cells` group is not touched.

**Acceptance criteria:**
- [ ] Copy's `:layout-grid-cells` matches the main's (remapped) after sync.
- [ ] Copy with `:layout-grid-cells` touched preserves its override.

**Verification:**
- [ ] Focused test goes GREEN.
- [ ] Existing `comp_sync_test` still passes.

### Checkpoint: Phase 2
- [ ] Reproduction test green + no regressions in `comp_sync_test`.

### Phase 3: Flex verification + polish
- [ ] Task 4: Verify flex conversion path; add coverage if missing.
- [ ] Task 5: Full common suite + lint/format.

**Verification:**
- [ ] `clojure -M:dev:test` (full JVM common suite) green.
- [ ] `pnpm run lint:clj` / `check-fmt:clj` clean (from `common/` or repo root as applicable).

### Checkpoint: Complete
- [ ] All acceptance criteria met.
- [ ] Ready for review.

## Testing Strategy

- JVM (fast, canonical for component/sync logic):
  `clojure -M:dev:test --focus common-tests.logic.comp-sync-test` (or a single var).
- Full common suite: `clojure -M:dev:test`.
- JS coverage (CLJC): `pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test`.
- Always redirect test output to a file first, then read it (never pipe to
  `head`/`tail`/`grep`). See `mem:testing`.
- Lint/format: `pnpm run lint:clj` and `pnpm run check-fmt:clj`.

Cover these cases:
- frame→grid conversion: copy gets cells matching main.
- main grid track reorder: copy cells follow (positions propagate).
- copy with `:layout-grid-cells` touched: override preserved.
- frame→flex conversion: child flex attrs propagate (regression guard).

## Parallelization Opportunities

- **Safe to parallelize:** flex coverage test (Task 4) is independent of the grid
  fix once the reproduction test exists.
- **Must be sequential:** Tasks 1 → 2 → 3 (RED before GREEN).
- **Needs coordination:** none (single module `common/`).

## Open Questions

- Whether `:shapes-group` (structural child reorder) should also be consulted in
  `update-grid-copy-attrs` beyond `:layout-grid-cells` — to be decided during
  implementation if the copy's `:shapes` order and cells can still diverge when
  only `:shapes-group` is touched.
