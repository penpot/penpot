# Frontend Workspace State and Persistence Subtleties

## Store and interaction streams

- `app.main.store/state` is the Potok store; `emit!` always returns nil. Store errors flow through the mutable `on-error` atom.
- `last-events` keeps a filtered rolling buffer of about 50 event type strings and commit hint origins. It intentionally omits noisy websocket/persistence/pointer events.
- `ongoing-tasks` controls `window.onbeforeunload`: any non-empty set blocks tab unload.
- `app.main.streams/wasm-modifiers` and `workspace-selrect` are behavior subjects used for high-frequency interactive preview state that bypasses normal store updates and lenses.
- Keyboard modifier streams merge a window blur signal so stuck modifier-key state is cleared after focus loss.

## Repo calls

- `app.main.repo/send!` uses GET only when the RPC name starts with `get-`, when all params are query params, or for configured special cases. Only GET requests are retried.
- GET retry is limited to transient `:network`, `:bad-gateway`, `:service-unavailable`, and `:offline` errors with exponential backoff. Mutations are not retried.
- A server SSE response is only accepted when the command is configured `:stream?`; otherwise it raises an unexpected-response assertion.

## Commits, undo, persistence

- `commit-changes` refuses to create commits unless `:permissions :can-edit` is true. It captures file revn/vern, selected-before, features, tags, undo group, and translation flag into a `::commit` event.
- Applying a remote commit first rolls back pending local commits, applies the remote changes, then replays pending local redo changes. Index updates are emitted for undo, remote redo, and replayed redo paths.
- Local commits are independently consumed by undo, persistence, WASM model updates, thumbnail/library watchers, and text position-data recalculation.
- Persistence buffers local commits: status becomes pending after about 200ms, commits are flushed after about 3s or `::force-persist`, and buffered commits are merged per file before `:update-file`.
- Persistence sends revn as the max of the commit revn and locally tracked latest revn; remote commits update that revn tracker.
- Persistence is skipped in version preview/read-only mode or without edit permission.
- Undo transactions can stay open only temporarily; timed-out pending transactions are force-committed after about 20s. Undo entries are capped at 50.
- Undo/redo are ignored while a normal editor/drawing interaction is active, except grid-layout edition handles undo through this path.
- After local commits and when render-wasm is active, text shapes get derived `:position-data` recomputed in a separate commit tagged `#{:position-data}`; that tag is excluded from the position-data watcher to avoid loops.

## Refs

- `refs/libraries` is explicitly deprecated for performance; prefer derefing `refs/files` and memoizing `select-libraries` in components.
- `refs/workspace-page-objects` uses `identical?` equality, so preserving object map identity matters for avoiding derived-ref churn.
- Selected-shapes refs use a small `{objects selected}` wrapper with custom equality before running `process-selected`; avoid bypassing that pattern in hot UI paths.