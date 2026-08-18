# Exporter Testing

- READ `mem:testing` first.
- Tests use `cljs.test` and live under `exporter/test/exporter_tests/`.
- Register every test namespace in `exporter-tests.runner`.
- From `exporter/`: `pnpm run build:test` builds the Node test bundle without running tests.
- From `exporter/`: `pnpm run test` builds and runs tests with full output.
- From `exporter/`: `pnpm run test:quiet` builds and runs tests with reduced build output.
- After `build:test`, reuse the compiled bundle with `node target/tests/test.js`.
- For iterative focused runs, build once and reuse the compiled bundle.
- Focus a test namespace with `node target/tests/test.js --focus exporter-tests.renderer-svg-test`.
- Focus a test var with `node target/tests/test.js --focus exporter-tests.renderer-svg-test/creates-the-correct-gradient-element`.
- Set app log level by appending `--log-level warn` (or `trace|debug|info|warn|error`).
- `test:quiet` accepts forwarded options but rebuilds the bundle; prefer the direct runner after `build:test` for focused runs.
- From `exporter/`: `pnpm run check-fmt:clj` checks ClojureScript formatting.
- From `exporter/`: `pnpm run lint:clj` runs ClojureScript linting.
