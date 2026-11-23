# Frontend Playwright integration tests

- Add, edit or run `frontend/playwright` integration specs only on explicit request. Test runner scripts and project names live in `frontend/package.json` and `frontend/playwright.config.js`; size workers to available CPU and browser memory.
- The web server serves the release bundle in `frontend/resources/public`, not live sources. Rebuild from `frontend/` with `./scripts/build` after product edits or a branch switch with different sources/assets; a spec-only edit does not require that rebuild. A stale bundle that fails at boot says nothing about the spec.
- Mock the RPC and socket traffic your page needs; the integration suite does not exercise live backend behavior. `BasePage.mockRPC` expects a command name, not the full RPC URL. Set up `BaseWebSocketPage` mocks before workspace initialization subscribes.
- Prefer user-facing locators. Layer rows are an exception: selection can add hide/lock titles to their accessible name. Use `WorkspacePage.layerRow`/`layerRows` and `selectRow`/`selectLayer` rather than matching the entire role name or clicking twice.
- For build-once CI and stable check names see `mem:frontend/e2e-ci-workflow`. For targeted live pointer and keyboard input see `mem:frontend/playwright-gestures`.
