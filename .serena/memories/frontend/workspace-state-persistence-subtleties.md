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
- Save failures split transient vs terminal (`transient-error?`: the repo retryable types `:network`/`:offline`/`:bad-gateway`/`:service-unavailable`/`:gateway-error` plus `:invalid-save-response`; everything else is terminal). Terminal keeps the `:error` halt + `flash-persistence` path; transient enters a `:retrying` episode: the head commit stays queued and resends with backoff 2s/8s/20s (3 retries, then the terminal path). A transient failure that exhausts the backoff arms a slow cycle (`slow-retry-delay-ms` 30s): `resume-persistence true` sets `:recovering`, so a transient failure goes straight back to `:error` (one send per cycle, no reconnect notice, no re-flash) and re-arms the cycle; `:recovering` and `:failing-since` clear when a head saves. The cycle and edit-driven resumes stop after `retry-give-up-ms` (24h, the backend redis TTL of a commit id). `:commit-in-progress` (backend turned away a repeat of a save still running) is transient. A stamped commit whose request left `active-requests` is simply sent again under the same `:commit-id` (the backend `file_commit` table applies a repeated commit id once), so there is no unknown-outcome halt; a request still in flight is never double-sent (`:in-flight` stays silent). A new edit on an `:error` queue resumes it from the head, and permission recovery resumes an already-attempted head too. Retry timers carry the episode token; a superseded token stays silent. Status stays `:retrying` through re-entries (`next-status` refuses `:pending`/`:saving` from it); waiters (`wait-persisted-or-error`) wait through it and reject only on `:error`.
- One reconnect notice per episode: sticky toast tagged `:persistence-reconnecting` (single-toast store, re-show replaces), hidden by tag on drain (`:saved`) and on terminal failure; recovery is silent. Header indicator has a `:retrying` state (`workspace.header.retrying`).
- Resume triggers: backoff timer and the browser `online` event (guarded by `exists? js/window`; re-enters the runner only for a live `:retrying` episode). New local edits during `:retrying` only join the queue: the episode keeps its `:run-id`, so `append-commit` does not re-enter, and the live runner (still waiting on the head's `commit-persisted`) sends them after the head. Re-entering on edits would bypass the backoff and spend an attempt per batched commit.
- Tests instant-trigger retries by stubbing `rx/timer` (recording delays to assert the schedule); dynamic bindings do not survive `await` continuations, so no dynamic var for delays.
- Undo transactions can stay open only temporarily; timed-out pending transactions are force-committed after about 20s. Undo entries are capped at 50.
- Undo/redo are ignored while a normal editor/drawing interaction is active, except grid-layout edition handles undo through this path.
- After local commits and when render-wasm is active, text shapes get derived `:position-data` recomputed in a separate commit tagged `#{:position-data}`; that tag is excluded from the position-data watcher to avoid loops.

## Refs

- `refs/libraries` is explicitly deprecated for performance; prefer derefing `refs/files` and memoizing `select-libraries` in components.
- `refs/workspace-page-objects` uses `identical?` equality, so preserving object map identity matters for avoiding derived-ref churn.
- Selected-shapes refs use a small `{objects selected}` wrapper with custom equality before running `process-selected`; avoid bypassing that pattern in hot UI paths.