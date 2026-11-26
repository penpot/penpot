# Frontend runtime crash recovery

- For a store exception, inspect `(:exception @app.main.store/state)`: `:type`, `:code`, `:status`, `:hint`, `:details` and `:uri` can identify the failing operation. Validation details can include shape IDs and invariant failures.
- A React error boundary can display an error without setting the store's `:exception`; a nil value alone does not prove the UI recovered. See `mem:frontend/error-reporting`.
- If the live REPL is available, `(.reload js/location)` reloads the browser; otherwise select and reload the workspace tab. Confirm both that the workspace renders and that the store exception is clear. For runtime selection see `mem:frontend/cljs-repl`.
- Before reloading, account for unsaved edits and partial saves: an earlier commit from a rejected batch can already be durable. Review `mem:frontend/workspace-persistence` before retrying edits.
- In a workspace, `execute_code` waits for save and fails on errors or rollback detected during that wait, but later failures can still occur. Dashboard calls skip the wait when `penpotMgmt` identifies that context; workspace readiness, plugin availability and rollback notice persistence have separate limits: see `mem:mcp/save-and-rollback` and `mem:frontend/plugin-save-events`.
