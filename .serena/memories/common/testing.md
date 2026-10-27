# Common Testing and Verification

`common/` is CLJC shared code. Tests should cover the relevant runtime(s): JVM for backend/common logic and JS for frontend/exporter behavior. For geometry, component, and file-model changes, JVM tests are common and fast, but JS/browser behavior can differ when WASM modifier math or CLJS-specific state is involved.

## Unit tests

READ `mem:testing` FIRST — it defines the execution discipline (no piping, tee to file, preferred commands) that applies to all CLJS/JS and JVM test runs.

Common tests live under `common/test/common_tests/` and use `clojure.test`.
They are CLJC and run on both JVM and JS.

From `common/`:
- Full JVM test run: `clojure -M:dev:test`
- Full JS test run (always builds, suppressed output): `pnpm run test:quiet`
- Full JS test run (always builds, build output visible): `pnpm run test`
- Focus a JVM test namespace: `clojure -M:dev:test --focus common-tests.logic.variants-switch-test`
- Focus a JVM test var: `clojure -M:dev:test --focus common-tests.logic.variants-switch-test/test-basic-switch`
- Focus a JS test namespace: `pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test`
- Focus a JS test var: `pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test/test-sync-when-changing-attribute`
- Quiet logging during a JS run: append `--log-level warn` (or `trace|debug|info|warn|error`)
- Build JS test target only (no run): `pnpm run build:test`
- After `build:test` has been run, run the compiled runner directly: `node target/tests/test.js [--focus ...] [--log-level ...]`.

New common JS test namespaces must be required/listed in `common_tests/runner.cljc`;
new vars in existing namespaces need no runner change. Multiple JVM `--focus` flags
compose as a union.

## Test helpers

Helpers live under `common/src/app/common/test_helpers/` and are usually aliased with short `th*` prefixes. Test namespaces using label->uuid helpers should start with `(t/use-fixtures :each thi/test-fixture)` so labels reset between tests.

Useful builders:
- `thf/sample-file` creates a base file.
- `tho/add-simple-component` creates a simple component.
- `thc/instantiate-component` instantiates a component copy.
- `thv/add-variant-with-child` creates a variant container with two child variants.
- `thv/add-variant-with-copy` creates variants whose children are component instances.

`add-variant-with-copy` does not accept position params for children; use `gsh/absolute-move` after creation if positions matter.

## Driving production paths

For shape mutations, prefer production-path helpers such as `cls/generate-update-shapes` plus `thf/apply-changes`. For component swaps with keep-touched behavior, use `tho/swap-component-in-shape` with `{:keep-touched? true}`.

`thf/apply-changes` validates by default and usually gives the most useful invariant failure. Pass `:validate? false` only for intentionally malformed intermediate state.

## Validation and repair tests

`common-tests.files.repair-components-test` has one test per component error code: break a file with
the helpers (`ths/update-shape`, `thc/update-component`), assert the exact set of codes, then repair
as the backend does (validate + `cfr/repair-file` until valid, at most 10 passes, like
`app.srepl.procs.file-repair`) and assert the result. Repairs that never converge are pinned with
`tm/known-failure`. A deleted component's objects are reached with `ctkl/get-component data id true`
(`thc/get-component` skips deleted ones). Validation often reports a second code on the same
shapes, whose repair runs in the same pass: check what the repair keeps (the copies' links, not
only the component) and run the code's own repair alone (`repair-once`) so another one cannot do
its work.

## Component sync benchmark

`common/dev/bench/component_sync.clj` times component sync on fixed-size worlds (one main into
10/100/1000 copies, 3 nesting levels, a chain of dependents, a variant switch, a library update,
10 copies among 20000 plain shapes).
Each world checks its result (how many rects end red) so it never times a no-op. Run from
`common/`: `scripts/bench-components` compares with `dev/bench/component_sync_baseline.edn` and
exits 1 when a world's lower quantile is more than 10% slower; `scripts/bench-components record`
rewrites the baseline (only on purpose, saying why). It takes about 4 minutes. Every commit that
touches sync, swap or change application runs it and quotes the table in its message. The lower
quantile is compared because the mean moves with background load (devenv watchers) and GC.

## Golden master

`common-tests.logic.golden-master-test` (JVM only) records the exact files the pure runner's
composable cases and the benchmark worlds produce: per variant, the state after each user, undo
and redo step (the runner's `:observe` option), then the final files with the round trip's
failure signatures. The frontend interpreter records its own for `comp.sync-test` and the
completion contract. Shared code: `app.common.test-helpers.golden-master` (alias `thgm`): ids
named by test label or `:unlabelled/<n>` in tree order, grid cells `:cell/<shape>.<row>.<col>`,
timestamps `:inst`, written as sorted `path value` lines. Only digests are committed
(`golden_master.edn` next to each test); full lines go to `tmp/golden-master/` and
`tmp/golden-master-frontend/` (`recorded/`, and `actual/` per differing state): read a change with
`diff -r tmp/golden-master/recorded tmp/golden-master/actual`. `recorded/` exists only on the
machine that recorded; elsewhere re-record at the parent commit to get it. A refactor must keep
the golden master; a behavior change records it again on purpose
(`PENPOT_GOLDEN_MASTER=record`, from `common/` for the JVM test, from `frontend/` for
`node target/tests/test.js --focus <ns>`; the frontend file is updated per namespace) and says
why in the commit. A new shared case goes into the test's `case-fns` and is recorded.

## Geometry setup caution

For geometry-sensitive tests, read `mem:common/geometry-invariants` before positioning shapes. Use geometry-preserving helpers or production change helpers rather than direct single-field edits.

## Debugging

Use `mem:common/component-debugging-recipes` for shape-tree dumps, undo/change inspection, and temporary live instrumentation recipes.

## Composable undo comparison

`comp/undo-check` keeps normalized snapshots exact, including `:touched` and
`:remote-synced`. Comparison permits only absolute floating noise below 1e-10
inside shape geometry: x/y, width/height, rotation, points, selrect and transform
matrices. Reflow can recompute 0.01 as 0.010000000000005116 after undo (F130).
This tolerance does not apply to opacity, other authored attrs or touched groups.
The regression rejects a 1e-5 height change and a 5e-15 opacity change.


## Generated component programs

`composable/gen` builds seeded EDN programs and resolves targets from current
shape-tree order. Run `common-tests.logic.composable-gen-test`; set
`PENPOT_GEN_NUM` and `PENPOT_GEN_SEED` to replay a count and seed. Before merge,
Phase 4 requires 5000 scenarios per property. `cg/run` accepts the printed
program and an optional expected trace; changed targets or skips fail replay.
Use separate fresh JVMs for concurrent checks: `world` resets the global test
label map, so concurrent futures in one JVM corrupt each other's fixtures.

The independent shadow tracks authored groups and values; the oracle checks
validation, touched membership, retained values, propagation and geometry.
Settlement also checks partial/full sync equivalence, idempotence and exact
undo/redo snapshots. Library routing in `comp/files` applies both the working
file and auxiliary file changes, then cascades both in the same undo group.

Stale-library and dependency generation includes source transforms on main
roots/children, letters and all twelve text style units. `:library-text true`
adds a separate source text component without reusing consumer shape IDs.
Source edits keep consumer history separate and copies stale until an
acceptance or command that explicitly refreshes that subtree. Requested
source geometry/text checks use the source editor's before-state and intent;
dropped source edits must fail I9. `common-tests.logic.source-gen-test`
covers the action matrix and independent source/consumer undo histories.

Backend parity uses `oracle/canonical` and `oracle/equivalent?`: IDs become
file/page/tree paths, flip
nil/false values become booleans, and derived layout/text dimensions and text
run splitting are omitted. Free copy-head placement after leaving a layout is
also omitted: pure execution has no reflow history. Each backend still checks
child offsets and propagation; a changed child offset fails the comparison.
These exclusions do not apply to undo snapshots or authored scalar values.
Swapped-head constraints and layout-item inputs use the position reference;
content inputs use the component's content main.


For long generated JVM checks, bound heap growth with -J-Xmx1536m (adjust
only if a run reports a real heap limit failure). Several uncapped JVMs can
fill host RAM and swap and make browser checks time out before tests start.
Keep concurrency low; use fresh processes for test-label isolation.

Canonical design data keeps unrounded geometry. Matrices are represented
by their effect on local shape bounds, so their tolerance has pixel units
rather than comparing dimensionless coefficients (F153). Undo snapshots
and golden masters keep raw matrices. Use oracle/equivalent? to compare
it: the absolute 0.01 tolerance applies only inside shape geometry. Rounding
first can split two positions 7e-7 px apart across a 0.005 boundary (F150).
Scalar values and touched stay exact. comp/undo-check/same-state? supplies
the shared comparison; its undo default remains 1e-10.

Measured auto-width/auto-height text compares its oriented first corner,
not the unrotated selrect x/y, which can move as height/width changes
(F220). Canonical comparison and requested geometry both retain that
anchor at the existing 0.01 tolerance. Layout-owned positions remain
derived; fixed text dimensions and raw undo geometry remain checked.

Swapped-head dimensions use the content main; position, rotation, flips,
constraints and layout-item inputs use the position source (F151).
When sizes differ, expected geometry keeps the position source's
untransformed top-left anchor after resizing. Resizing around a rotated
corner shifts that anchor and creates a false I4 failure (F167).
A detached swapped head that becomes a plain copy loses swap-group without
inheriting its former position's groups. Descendants whose refs do not change
keep their existing shadow groups (F155).

For free copy-head placement, expected geometry keeps the placement matrix
it applied. Re-decomposing a 0.01px frame can add scale noise that becomes
a .011px error in its larger children (F161). Child geometry, scalar values
and undo comparisons remain checked; this does not widen their tolerance.

Detach's shadow expresses inherited geometry in the surviving copy's frame
before merging groups (F163). Compute orientation from the source and copy
before detaching, never from the resulting child, whose geometry is checked.

The switch shadow compares numeric geometry of the two mains with float
tolerance too (F164). A 1e-12 size difference must keep a resize override;
a 1px difference must discard it. Nongeometry values remain exact.

Expected child placement takes unit axis directions from the parent
matrices. Resize already runs through dimensions and constraints; a
minimum-size clamp can leave residual matrix scale that must not resize
fixed children again (F173). Canonical matrices and undo remain raw. The
fixture still rejects a real100→80px fixed-child height change.

Update-main's shadow uses the source values and the new near reference to
predict content overrides when a pushed swap becomes an ordinary nested
copy (F174). Read the reference id from the new shape for correspondence,
but never read its resulting values or touched groups to decide what the
user authored. The negative control corrupts both promoted head and child
fills and still predicts the source fills.

A switch retains orientation supplied by the old component even when the
old copy has no geometry override (F175). Predict this choice from the old
root and the new expected parent placement. A negative control removes the
new root's rotation; the shadow must still expect the old angle. Ordinary
position inheritance after swapping back must sync without creating new
groups (F176); the same shrunk program runs through the pure and async
frontend paths.

Retained orientation must merge into a geometry group that already carries
size (F178). Its presence does not mean the angle was carried: the two
variant mains can agree on size and disagree on rotation. The control keeps
the expected80px height and90deg angle even if the result loses its angle.

Duplicate candidates and pure Duplicate nodes use cld/allow-duplicate?, the
normal frontend eligibility guard (F181). Finite repeated component nesting
can still be unsafe to duplicate inside its main; the portable regression
checks refusal, mixed batches and allowed ALT duplication at the page root.
This is an operation precondition, not an oracle exception.

The switch shadow follows an authored swapped near head to its content
main, using the model's swap group and component identity (F184). Following
the replaced position instead can lose an inherited fill override. Keep
these choices independent of the resulting touched set and values. The
negative control ignores a different fill on the replaced position and
still expects the authored fill if the switch result is corrupted.
