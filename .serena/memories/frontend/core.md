# Frontend Architecture and Workflow

Frontend: CLJS SPA; React/Rumext; Potok; RxJS; okulary refs; SCSS modules; shared `common/`; JS/TS workspace packages.

## Stable namespace map

- `app.main.ui.*`: Rumext/React UI components for workspace, dashboard, viewer, settings, auth, nitrate, etc.
- `app.main.data.*`: Potok event handlers and side effects.
- `app.main.refs`: reactive refs/lenses over store and derived workspace data.
- `app.main.store`: Potok store and `emit!`.
- `app.plugins.*` and `app.plugins`: CLJS implementation of Plugin JS API proxies.
- `app.render_wasm.*`: frontend bridge to Rust/WASM renderer.
- `app.util.*`: DOM, HTTP, i18n, keyboard, codegen, and general frontend utilities.
- `frontend/packages/*` and `frontend/text-editor`: JS/TS workspace packages consumed by the app.
- Nitrate subscription/organization UI and flows live under `app.main.data.nitrate` and `app.main.ui.nitrate*`; backend/API behavior is covered by backend memories, and shared permission rules are in `common/src/app/common/types/nitrate_permissions.cljc`.


## Lint and Format

From `frontend/`:

- CLJ/CLJS lint: `pnpm run lint:clj`.
- JS lint currently no-ops via `pnpm run lint:js`.
- SCSS lint: `pnpm run lint:scss`.
- Format checks: `pnpm run check-fmt:clj`, `pnpm run check-fmt:js`, `pnpm run check-fmt:scss`.
- Format fix: `pnpm run fmt`, or targeted `fmt:clj` / `fmt:js` / `fmt:scss`.
- Translation formatting after i18n edits: `pnpm run translations`.

**Before linting:** if delimiter errors are suspected (after LLM edits, or
lint/compiler reports syntax errors), run `tools/paren-repair.bb` on the
affected files first. Delimiter errors produce misleading linter output.
See `mem:tools/paren-repair`.

## Focused memory routing

UI and packages:
- App UI components, SCSS modules, style-system boundaries, accessibility, i18n, and render performance: `mem:frontend/ui-conventions-and-style-system`.
- JS/TS packages, shared UI package, text editor, Storybook, and package builds: `mem:frontend/ui-packages-text-editor-workflow`.

Workspace behavior:
- Workspace state, commits, persistence, undo, repo calls, and refs: `mem:frontend/workspace-state-persistence-subtleties`.
- Workspace transforms, modifier previews, WASM modifier integration, and transform commits: `mem:frontend/workspace-transform-subtleties`.
- Workspace token application/propagation: `mem:frontend/workspace-token-subtleties`; shared token data/schema: `mem:common/tokens-schema-subtleties`.

App shell and product flows:
- Routing, root app shell, websocket, and global errors: `mem:frontend/routing-app-shell-subtleties`.
- Dashboard and viewer flows: `mem:frontend/dashboard-viewer-subtleties`.
- Plugin JS API runtime inside the frontend app: `mem:frontend/plugin-api-to-cljs-binding`.

Diagnostics and validation:
- Runtime inspection and navigation: `mem:frontend/cljs-repl`.
- Source-edit compile/hot-reload diagnostics: `mem:frontend/compile-diagnostics`.
- Runtime crash recovery: `mem:frontend/handling-crashes`.
- Tests and live verification: `mem:frontend/testing`.
- Real pointer/keyboard gesture reproduction: `mem:frontend/playwright-gestures`.

## Areas without focused memories

These frontend areas currently have no dedicated Serena memory beyond this architecture entry and nearby source/tests: clipboard, drawing tools, boolean/path operations, interactions/prototyping, color/style asset management, grid-layout editing UI, comments UI, fonts UI, and many dashboard/settings subflows. Treat work there as less memory-covered and inspect source/tests more carefully.
