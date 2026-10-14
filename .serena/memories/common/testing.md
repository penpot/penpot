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
