# Common Testing and Verification

`common/` is CLJC shared code. Tests should cover the relevant runtime(s): JVM for backend/common logic and JS for frontend/exporter behavior. For geometry, component, and file-model changes, JVM tests are common and fast, but JS/browser behavior can differ when WASM modifier math or CLJS-specific state is involved.

## Unit tests

Common tests live under `common/test/common_tests/` and use `clojure.test`.
They are CLJC and run on both JVM and JS.

From `common/`:
- Full JVM test run: `clojure -M:dev:test`
- Full JS test run: `pnpm run test:quiet`
- Focus a JVM test namespace: `clojure -M:dev:test --focus common-tests.logic.variants-switch-test`
- Focus a JVM test var: `clojure -M:dev:test --focus common-tests.logic.variants-switch-test/test-basic-switch`
- Focus a JS test namespace: `pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test`
- Focus a JS test var: `pnpm run test:quiet -- --focus common-tests.logic.comp-sync-test/test-sync-when-changing-attribute`
- Quiet logging during a JS run: append `--log-level warn` (or `trace|debug|info|warn|error`)
- Build JS test target only: `pnpm run build:test`
- After `pnpm run build:test`, direct compiled runner: `node target/tests/test.js --focus common-tests.logic.comp-sync-test/test-sync-when-changing-attribute --log-level warn`
- Watch tests: `pnpm run watch:test`

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

## Geometry setup caution

For geometry-sensitive tests, read `mem:common/geometry-invariants` before positioning shapes. Use geometry-preserving helpers or production change helpers rather than direct single-field edits.

## Debugging

Use `mem:common/component-debugging-recipes` for shape-tree dumps, undo/change inspection, and temporary live instrumentation recipes.
