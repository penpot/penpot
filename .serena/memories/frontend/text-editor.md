# Frontend text editor

- `frontend/text-editor` is the JS/Vite `@penpot/text-editor` package, with its own Vitest and browser-test setup. Its entry point is `src/editor/TextEditor.js`; shared text data rules are in `mem:common/text-subtleties`.
- It copies render-wasm artifacts from `frontend/resources/public/js` to `src/wasm`. After rebuilding render-wasm, run `pnpm run wasm:update` from `frontend/text-editor` when local development or tests need updated artifacts.
- From `frontend/text-editor`, `pnpm run dev` starts Vite, `pnpm run test` runs tests, `pnpm run coverage` collects coverage, and `pnpm run test:watch:e2e` starts browser tests. `pnpm run fmt:js` checks formatting. Frontend root JS formatting also covers text-editor sources, but does not replace package tests.
- Text behavior can cross this package, render-wasm and CLJS integration; verify the runtime that owns the changed behavior. For the separate shared UI package see `mem:frontend/ui-package`.
