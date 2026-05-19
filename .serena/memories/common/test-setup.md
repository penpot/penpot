# Common Module Test Setup

`common/` is CLJC shared code. Tests should cover the relevant runtime(s): JVM for backend/common logic and JS for frontend/exporter behavior. For geometry, component, and file-model changes, JVM tests are common and fast, but JS/browser behavior can differ when WASM modifier math or CLJS-specific state is involved.

## Running tests

From `common/`:

```bash
pnpm run test:jvm
clojure -M:dev:test
pnpm run test:jvm --focus common-tests.logic.variants-switch-test
clojure -M:dev:test --focus common-tests.logic.variants-switch-test/test-basic-switch
pnpm run test:js
pnpm run watch:test
```

Focused JS tests are selected by editing `test/common_tests/runner.cljs`, then running `pnpm run test:js`. Multiple JVM `--focus` flags compose as a union.

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