# Frontend Plugin API Runtime Subtleties

## Type declarations vs runtime

- The Plugin API is a public facade over internal frontend/common data. Do not expect Plugin API property names, value shapes, or behavior boundaries to match internal CLJS attrs or helper APIs; inspect the relevant proxy and internal code path before using Plugin API observations in production internals or tests.
- `plugins/libs/plugin-types/index.d.ts` contains TypeScript declarations only. Runtime objects are CLJS proxies built under `frontend/src/app/plugins/*.cljs` with `obj/reify`.
- `shape.cljs` builds shape proxies with hidden ids and per-property CLJS implementations. `library.cljs` builds library proxies such as `LibraryComponentProxy`.
- `shape.cljs`, `library.cljs`, and related namespaces break circular dependencies with mutable nil vars patched from `app.plugins` at load time. If a proxy constructor appears nil, check the patching path in `frontend/src/app/plugins.cljs`.

## Key Domain Namespaces
- `app.common.types.component` (aliased `ctk`) — component predicates: `instance-root?`, `instance-head?`, `in-component-copy?`, `is-variant?`
- `app.common.types.container` (aliased `ctn`) — container/tree operations: `in-any-component?`, `get-instance-root`, `get-head-shape`, `inside-component-main?`
- `app.common.types.file` (aliased `ctf`) — file-level operations: `resolve-component`, `get-ref-shape`

## Runtime initialization and permissions

- The frontend initializes `@penpot/plugins-runtime` only after `features/initialize` and only when feature `plugins/runtime` is active. It also installs the runtime `isPluginError` predicate into frontend error handling.
- Manifest parsing expands write permissions to read permissions (`content:write` => `content:read`, etc.). Permission checks also allow the all-zero plugin id and the hard-coded MCP plugin id.
- Manifest URL origin differs by manifest version: v1 clears the path; v2 joins `.` to the plugin URL. Existing plugin ids are reused by matching manifest name and host.
- The MCP plugin id is defined in `app.plugins.register` to avoid a circular dependency with workspace MCP code.

## Proxy behavior

- Public Plugin API objects are lightweight handles, not durable snapshots. Most getters locate fresh state from `app.main.store/state` using hidden `$id`, `$file`, `$page`, etc.
- `not-valid` logs by default but throws when the plugin flag `throwValidationErrors` is enabled. The MCP execute-code handler deliberately enables that flag while running code.
- A write to a removed shape always throws `Shape not found`, whatever that flag: setters through the shape proxy's `:on-set u/check-shape-writable` (runs outside the error handling), and methods that must not vanish silently (`resize`, `rotate`) call `u/check-shape-located` first. Methods run inside `:on-error (u/handle-error ...)`, which logs instead of throwing when the flag is off, so it rethrows errors whose `ex-data :type` is `::u/shape-not-found` or `::r/read-only`.
- A write while the workspace cannot take it (`r/read-only-reason`: save-failure lock `[:workspace-global :persistence-lock]`, `[:permissions :can-edit]` false, version preview `:preview-id`, lost render context `[:render-state :lost]`) always throws, whatever that flag. The `[:workspace-global :read-only?]` flag alone does not block: the Inspect tab sets it and plugins may still write. `r/check-permission` with `content:write`/`library:write` calls `r/check-writable` first, which covers most writers; writers without a permission check (most top-level context methods in `api.cljs`: `create*`, `group`, `align*`, …; z-order methods; text range `applyTypography`; ruler guide `remove`) call `r/check-writable` themselves, and shape setters get it through `u/check-shape-writable`. A new writer without a write-permission check must call it too. An async writer calls it again after each server await, before the next server write or commit (`rx/tap` on the response stream; `dwm/create-svg-shape-with-images` takes it as `:check-writable`), so a lock that lands mid-way rejects the promise with the read-only error. `r/has-permission?` is the check without it, used by the undo blocks so a block opened before a lock still closes. Comments (`comment:write`) stay writable: they do not go through file saves.
- `penpotMgmt` (`app.plugins.management`) checks permissions with `r/has-permission?` (`granted?`), never `r/check-permission`: its calls act on files through the backend, often from the dashboard with no file open, so the workspace read-only check (`check-writable`) must not block them.
- `naturalChildOrdering` and `throwValidationErrors` are stored per plugin under `[:plugins :flags plugin-id ...]`; changing default behavior affects automation and MCP diagnostics.
- Plugin data is stored under keyword namespaces: private data uses `(keyword "plugin" plugin-id)`, shared data uses `(keyword "shared" namespace)`.

## Events and history

- Plugin listeners are watches on the global store and callbacks are debounced about 10ms. Callback exceptions are caught and logged so plugin code does not crash the app.
- `selectionchange` callbacks receive arrays of shape id strings, while `filechange`, `pagechange`, and `shapechange` return proxies.
- `contentsave` fires only when persistence status transitions to `:saved`; it calls the callback with no value.
- `contentrollback` is not a state watch: it subscribes to `st/stream` for `::dps/rolled-back` (kept in `subscriptions`, disposed by `remove-listener`), not debounced, delivered async so the reverted state is in place. Payload `{fileIds pageIds shapeIds}` from `pcm/rollback-targets` (page shapes only, from the reverted commits' redo changes). When the rollback falls back to reloading the file, `::dps/rolled-back` waits for the reloaded data, so the callback never reads the rejected data. The runtime's `validEvents` must list every event name, or `penpot.on` rejects it; a stale runtime build rejects new events. The MCP plugin forwards these rollbacks with the next task response and the server puts a warning first in that tool response (`RollbackNotices`). Unsent ones survive a tab or plugin reload in `penpot.localStorage` (key `rollbacks/<file-id>`, `LocalStorageRollbackStore`; the MCP manifest in `app.main.data.workspace.mcp` and `mcp/packages/plugin/public/manifest.json` grant `allow:localstorage`, which the runtime checks on access); the entry goes once a response carried them.
- Plugin history `undoBlockBegin` creates a workspace undo transaction with a JS `Symbol`; `undoBlockFinish` commits that symbol. Missing finish eventually relies on the workspace transaction timeout.