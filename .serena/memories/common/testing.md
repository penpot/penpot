# Common testing

- **Read `mem:testing` first** for test design and output discipline. Common tests are CLJC under `common/test/common_tests/`; cover the relevant JVM and JS runtimes. JVM tests alone cannot prove CLJS-specific state or WASM behavior.
- From the repo root, `./scripts/ci common` runs lint, tests and format. From `common/`, use `clojure -M:dev:test` for JVM tests and `pnpm run test:quiet` for JS tests. Both support focused runs, e.g. `--focus common-tests.logic.comp-sync-test`; repeat `--focus` for multiple JVM selections.
- JS tests compile before running. Register new namespaces in `common_tests/runner.cljc`; new vars in registered namespaces need no runner change. Direct `node target/tests/test.js` runs the existing build and does not rebuild after edits.
- JVM coverage uses `clojure -M:dev:test:coverage`; it instruments all configured namespaces, even for a focused run, and is slower. Its lcov, HTML and EMMA form counts go under `target/coverage/`; see `tests.edn` for exclusions.
- Helpers live under `common/src/app/common/test_helpers/`. Tests using label-to-UUID helpers need `(t/use-fixtures :each thi/test-fixture)` to reset labels between tests. Inspect the builders there rather than recreating common file, component or variant setup.
- Prefer production-path mutations such as `cls/generate-update-shapes` with `thf/apply-changes`; the latter validates by default. Disable validation only for intentionally malformed intermediate state. For swaps that keep touched state use `tho/swap-component-in-shape` with `{:keep-touched? true}`. `add-variant-with-copy` does not take child position params; move them after creation. For geometry-sensitive setup see `mem:common/geometry`.
- `sg/sample` is unseeded by default. If a test filters for a subtype, fix the seed and verify the subtype exists; otherwise `first` can yield nil and later produce a typeless shape.
- For component and referential-integrity debugging see `mem:common/components`.
