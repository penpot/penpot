# Frontend Routing, App Shell, Websocket, and Error Subtleties

## Router, app shell, and errors

- Routing uses browser-history query tokens (`?screen=<route-name>&params`, single `/` path), but `on-navigate` rejects navigation if the current origin/path does not match `cf/public-uri`.
- Route params live entirely in the query map under the reserved `screen` key; duplicate query params can become vectors, so use `rt/get-query-param` when a scalar is required.
- Legacy `#/…` hash URLs translate client-side to the query format (one-version compat; see `legacy-routes` in `app.main.ui.routes`, TODO(next-version) to delete).
- Unknown/empty routes trigger an extra `get-profile`/`get-teams` check before redirecting. This avoids invitation and root-route race conditions.
- The root app renders an exception page from `:exception` state before the normal error boundary. `rt/navigated` clears `:exception`.
- Frontend error handling treats stale cross-build JS chunk failures specially: messages containing `$cljs$cst$` or `$cljs$core$I` plus undefined/null/not-a-function signatures trigger throttled reload.
- Plugin-originated uncaught errors are identified through the plugin runtime hook and logged rather than turning into the global exception page.
- `app.main.errors/submit-report` is governed by a dedup governor: each report carries a fingerprint (`report-name|type|code|hint|first stack frame`; the report name is part of it so a handled report never coalesces with an unhandled/exception-page one), the first occurrence is always emitted, repeats within 2 minutes are counted and included in the next emitted report as `:occurrences`, and the fingerprint cache is bounded (first-inserted entry evicted, FIFO, via `:order` queue) so memory stays fixed. It applies to `handled-exception`, `unhandled-exception` and `exception-page`.
- `generate-report` is total: if formatting fails it returns a minimal fallback string instead of nil, so an already reserved emission is never dropped.
- `flash` reserves the report before generating it, so suppressed occurrences do not pay the `generate-report` cost; the toast is unchanged.

## Store and websocket

For general store mechanics such as `emit!`, `last-events`, persistence, and undo, read `mem:frontend/workspace-state-persistence-subtleties`.

- Websocket initialization uses `cf/public-uri` joined with `ws/notifications`, converting `http/https` to `ws/wss`, and includes the current `session-id` as query param.
- Reinitializing or finalizing websocket stops the previous receive stream. Incoming websocket payloads become Potok data events under `app.main.data.websocket/message`.