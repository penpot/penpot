# Frontend Routing, App Shell, Websocket, and Error Subtleties

## Router, app shell, and errors

- Routing uses browser-history hash tokens, but `on-navigate` rejects navigation if the current origin/path does not match `cf/public-uri`.
- Route params are split into `:path` and `:query`; duplicate query params can become vectors, so use `rt/get-query-param` when a scalar is required.
- Unknown/empty routes trigger an extra `get-profile`/`get-teams` check before redirecting. This avoids invitation and root-route race conditions.
- The root app renders an exception page from `:exception` state before the normal error boundary. `rt/navigated` clears `:exception`.
- Frontend error handling treats stale cross-build JS chunk failures specially: messages containing `$cljs$cst$` or `$cljs$core$I` plus undefined/null/not-a-function signatures trigger throttled reload.
- Plugin-originated uncaught errors are identified through the plugin runtime hook and logged rather than turning into the global exception page.

## Store and websocket

For general store mechanics such as `emit!`, `last-events`, persistence, and undo, read `mem:frontend/workspace-state-persistence-subtleties`.

- Websocket initialization uses `cf/public-uri` joined with `ws/notifications`, converting `http/https` to `ws/wss`, and includes the current `session-id` as query param.
- Reinitializing or finalizing websocket stops the previous receive stream. Incoming websocket payloads become Potok data events under `app.main.data.websocket/message`.