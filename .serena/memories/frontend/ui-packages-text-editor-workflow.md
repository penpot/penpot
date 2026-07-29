# Frontend UI Packages and Text Editor Workflow

`frontend/packages/`, `frontend/text-editor/`, Storybook/component tests. Separate from CLJS app UI under `frontend/src/app/main/ui`.

## Package boundaries

- `frontend/packages/ui` builds `@penpot/ui`, a React/Vite library package. It exports ESM and type declarations from `dist/`; React and ReactDOM are peer dependencies and must stay external in the Vite library build.
- The UI package build copies generated `dist/index.css` into `frontend/resources/public/css/ui.css`. If shared UI styles look stale in the app, rebuild the package or check this copy step before debugging CLJS style code.
- `frontend/text-editor` builds `@penpot/text-editor` from `src/editor/TextEditor.js`. It is a Vite JS package, not CLJS, and has its own Vitest/browser-test setup.
- The text editor consumes render-wasm artifacts copied from `frontend/resources/public/js` into `frontend/text-editor/src/wasm`. Use `pnpm run wasm:update` after rebuilding `render-wasm` if tests or local dev use stale WASM files.
- Other packages under `frontend/packages/` such as `tokenscript`, `draft-js`, and `mousetrap` are workspace dependencies used by the frontend app; do not assume their runtime behavior lives in CLJS namespaces.

## Commands

From `frontend/`:
- Build app-side JS package assets: `pnpm run build:app:libs`.
- Watch app-side JS package assets: `pnpm run watch:app:libs`.
- Storybook build: `pnpm run build:storybook`; local Storybook: `pnpm run watch:storybook`.
- Storybook/component tests: `pnpm run test:storybook`.

From `frontend/packages/ui`:
- Build library and CSS artifact: `pnpm run build`.
- Watch library build: `pnpm run watch`.

From `frontend/text-editor`:
- Local Vite dev: `pnpm run dev`.
- Tests: `pnpm run test`; coverage: `pnpm run coverage`; browser watch: `pnpm run test:watch:e2e`.
- Format check: `pnpm run fmt:js`.

## Validation notes

- Frontend root `check-fmt:js` covers stories, Playwright scripts, frontend scripts, and `text-editor/**/*.js`; it does not replace package-specific builds/tests.
- Changes to shared UI package exports should be validated both in the package build and in the consuming app/Storybook path.
- Changes that alter text rendering/editing can involve `frontend/text-editor`, `render-wasm`, CLJS text integration, and `mem:common/text-subtleties`; verify the runtime that actually owns the changed behavior.