# Frontend testing

- Start with `mem:testing` for test design and output discipline. For lint, unit tests and format together, run `./scripts/ci frontend` from the repo root; see `mem:scripts/ci`.
- Testing CLJS events, helpers or UI logic against mocks: `mem:frontend/testing-unit`. Testing component behavior through composable commands: `mem:frontend/composable-component-tests`.
- Running or editing integration specs with a release bundle: `mem:frontend/testing-playwright` (only on explicit request). For CI jobs that consume that bundle, see `mem:frontend/e2e-ci-workflow`.
- JVM tests alone do not exercise frontend state, the WASM bridge or browser gestures. For a live check use `mem:frontend/cljs-repl` or `mem:frontend/playwright-gestures`. For failed compile or hot reload use `mem:frontend/compile-diagnostics`; a runtime crash is `mem:frontend/handling-crashes`.
