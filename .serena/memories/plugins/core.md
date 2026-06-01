# Plugins Architecture and Workflow

`plugins/`: standalone TypeScript/pnpm workspace for Plugin API packages and sample plugins. Related to, distinct from, frontend CLJS Plugin API runtime.

## Layout

- `libs/plugin-types`: TypeScript declarations for the public Penpot Plugin API. Type-only package; runtime behavior is implemented elsewhere.
- `libs/plugins-runtime`: runtime that loads plugins and exposes/generated API behavior to plugin code.
- `libs/plugins-styles`: reusable styling package for plugins.
- `apps/*-plugin`: sample/development plugins. `apps/e2e`: plugin e2e tests.

## Dev Workflow

- From `plugins/`: install `pnpm -r install`; runtime dev server `pnpm run start` or `pnpm run start:app:runtime`; sample plugin `pnpm run start:plugin:<name>`; build runtime `pnpm run build:runtime`; build plugins `pnpm run build:plugins`; lint `pnpm run lint`; format `pnpm run format:check` / `pnpm run format`; tests `pnpm run test`; e2e `pnpm run test:e2e`.
- If a change affects public Plugin API types or runtime, update `plugins/CHANGELOG.md`. Prefix type/signature entries with `**plugin-types:**`; runtime behavior entries with `**plugin-runtime:**`.
- JS Plugin API behavior inside Penpot app: `mem:frontend/plugin-api-to-cljs-binding`; TS declarations are not runtime code; many API objects are CLJS proxies in `frontend/src/app/plugins/*.cljs`.

## Sandbox and global cleanup

- The runtime uses SES compartments. Public API return values are passed through `ses.safeReturn` before crossing back to plugin code.
- Plugin `fetch` is sanitized: credentials are omitted and Authorization is blanked. The exposed response only includes ok/status/statusText/url/text/json.
- Timer callbacks are wrapped to mark plugin-originated errors, and timeout/interval IDs are tracked so plugin close can clear them.
- Plugin-originated errors are tracked in a WeakMap instead of mutating error objects, because SES can freeze errors.
- Closing a plugin removes public API keys from the compartment globalThis.

## Lifecycle

- Loading a plugin closes existing non-background plugins and resets the runtime registry. Be careful around `allowBackground` semantics when changing load/close behavior.
- If sandbox evaluation fails, the runtime marks the error as plugin-originated, closes the plugin, and rethrows.
- `plugin-manager` removes event listeners, timers, intervals, and modal state on close, and marks the plugin destroyed. Listener callbacks check that flag because Penpot events can fire after close.

## Modal/UI behavior

- Modal URL preparation differs by manifest version: v1 uses query string parameters, v2 puts parameters in the URL hash.
- `openModal` is idempotent for the same iframe source and avoids reopening when the target URL is already displayed.
- Modal permissions are derived from manifest permissions (`allow:downloads`, `clipboard:read`, `clipboard:write`).
- `resizeModal` clamps to at least 200x200 and at most the window minus margins, adjusting transform so the modal remains in the viewport.