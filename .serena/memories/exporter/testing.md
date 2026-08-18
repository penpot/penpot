# Exporter Testing

- READ `mem:testing` first.
- Tests use `cljs.test` and live under `exporter/test/exporter_tests/`.
- Register every test namespace in `exporter-tests.runner`.
- From `exporter/`: `pnpm run build:test` builds the Node test bundle.
- From `exporter/`: `pnpm run test` builds and runs tests with full output.
- From `exporter/`: `pnpm run test:quiet` builds and runs tests with reduced build output.
- From `exporter/`: `pnpm run check-fmt:clj` checks ClojureScript formatting.
- From `exporter/`: `pnpm run lint:clj` runs ClojureScript linting.
- GitHub Actions runs exporter format, lint, and tests for `exporter/**` and `common/**` changes.
