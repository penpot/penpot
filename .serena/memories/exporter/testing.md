# Exporter testing

- READ `mem:testing` first. Tests use `cljs.test` under `exporter/test/exporter_tests/`. Add each test namespace both to the `:require` list and to `test-namespaces` in `exporter-tests.runner`.
- For a focused run, from `exporter/` compile with `pnpm run build:test`, then run `PENPOT_SECRET_KEY=test-secret-key node target/tests/test.js --focus exporter-tests.renderer-svg-test` (append `/test-var` to focus a var). Recompile after source or test changes: the direct runner executes the existing bundle. `--log-level warn` adjusts application logging.
- `pnpm run test` supplies a default test secret, but the direct runner and `test:quiet` do not; set `PENPOT_SECRET_KEY` when running them directly. `test:quiet` rebuilds even when forwarding a focus option. For the full module checks, including the test secret, see `mem:scripts/ci`.
