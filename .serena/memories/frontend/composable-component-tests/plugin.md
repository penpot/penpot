# Composable tests through the Plugin API

- Use `plugins/apps/composable-test-suite/README.md` for authoring, interactive runs, Playwright control and CI commands. The app and Plugin API are real; the headless driver mocks backend RPC and WebSocket traffic. A passing run verifies frontend propagation, not persistence or backend integration.
- Keep variant IDs stable across selection and streamed results. The test engine reports through its observer, not the panel or CI driver; keep UI and logging adapters outside it.
- The mocked workspace setup overlaps with the frontend Playwright harness and the separate Plugin API test suite; consult the provenance comment in `plugins/apps/composable-test-suite/ci/run-ci.ts` before changing routes or fixtures. For the prebuilt frontend bundle contract see `mem:frontend/e2e-ci-workflow`.
