# Workspace store and refs

- `app.main.store` owns the Potok store; `app.main.refs` provides derived refs. `emit!` dispatches and returns nil, not the result of a watch. To inspect a live stream, use `mem:frontend/cljs-repl`.
- `app.main.streams` keeps high-frequency WASM modifier and selection-rectangle previews out of the normal store to avoid ref churn; do not treat those previews as committed shape data. See `mem:frontend/workspace-transform-subtleties`.
- Preserve page-object map identity when possible: `refs/workspace-page-objects` compares with `identical?`. Keep the selection input equality check before processing selected shapes. Prefer selecting libraries from `refs/files` with component memoization over the broad deprecated `refs/libraries`.
- Keyboard modifier state must clear on window blur. Focus mode tracks added and removed objects as the page changes; exit when none remain and restore the surviving pre-focus selection. Otherwise a later draw can re-arm a mode the user already left.
- Reloading a workspace initializes again without finalizing the existing one; finalizing would drop transient state. Consumers started by initialization must stop on a newer initialization, not just on exit, or reload doubles undo and other side effects.
- Undo and remote rebase: `mem:frontend/workspace-commits`. Save queue and unload guard: `mem:frontend/workspace-state-persistence-subtleties`.
