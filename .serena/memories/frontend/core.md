# Frontend

Start at `app.main.ui` for Rumext components, `app.main.data` for Potok events, and `app.main.refs` for derived state. Shared model rules live in `mem:common/core`.

## UI and integration

- CLJS app components, accessibility and Design System: `mem:frontend/ui-cljs`. SCSS modules and Design System tokens: `mem:frontend/ui-styles`.
- Shared `frontend/packages/ui` React library and Storybook: `mem:frontend/ui-package`. JS text editor and render-wasm artifacts: `mem:frontend/text-editor`.
- PO files and locale conventions: `mem:frontend/translations`.
- JS Plugin API bindings and proxies: `mem:frontend/plugin-api-to-cljs-binding`; plugin save waits and rollback events: `mem:frontend/plugin-save-events`. External SaaS hooks and script load order: `mem:frontend/host-integration`.

## Workspace

- Store, refs, and interaction streams: `mem:frontend/workspace-store`.
- Local and remote commits, rebase, and undo: `mem:frontend/workspace-commits`.
- Save queue, rejected edits, retries, and recovery: `mem:frontend/workspace-state-persistence-subtleties`.
- Modifier previews, transform commits, and WASM bridge: `mem:frontend/workspace-transforms`.
- Token application and propagation: `mem:frontend/workspace-token-subtleties`; token model: `mem:common/tokens-schema-subtleties`.

## App shell

- Router, history, SSO destinations and team context: `mem:frontend/routing-app-shell-subtleties`.
- Authentication event sources and invitations: `mem:frontend/auth-events`.
- Error boundary, transport errors and reports: `mem:frontend/error-reporting`.
- WebSocket lifetime, membership revocation, and job progress: `mem:frontend/websocket-notifications`.
- Dashboard state and invitation controls: `mem:frontend/dashboard`. Import analysis and uploads: `mem:frontend/imports`. Viewer routes and bundle resolution: `mem:frontend/viewer`.

## Testing and diagnosis

- Choose the frontend test suite: `mem:frontend/testing`. Run `./scripts/ci frontend` from the repo root for lint, unit tests and format; see `mem:scripts/ci`.
- Failed compile, stale hot reload, or compiler warnings: `mem:frontend/compile-diagnostics`.
- Inspect the live browser, navigate or observe events: `mem:frontend/cljs-repl`. For delayed Internal Error pages, see `mem:frontend/handling-crashes`.
- Real pointer or keyboard input: `mem:frontend/playwright-gestures`.
