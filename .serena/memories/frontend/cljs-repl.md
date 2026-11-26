# Live frontend CLJS inspection

- Use an available CLJS REPL integration or `scripts/nrepl-eval.mjs --frontend` for the running frontend; see `mem:scripts/nrepl-eval` for session reuse and error handling. Tool availability depends on the active client. Penpot MCP `cljs_repl`, when available, starts each call in `cljs.user`.
- Multiple shadow-cljs runtimes can be attached, including the rasterizer. Check `(.-title js/document)` before inspecting or changing state. From `frontend/`, `shadow.cljs.devtools.api/repl-runtimes` lists runtimes and `cljs-eval` accepts `:client-id`; use command timeouts for a disconnected browser:

```bash
printf '(shadow.cljs.devtools.api/repl-runtimes :main)\n' | timeout 10 npx shadow-cljs clj-eval --stdin
printf '(shadow.cljs.devtools.api/cljs-eval :main "<cljs-code>" {:client-id 5})\n' | timeout 10 npx shadow-cljs clj-eval --stdin
```

- Inspect `app.main.store/state` for workspace metadata and selection; use `@app.main.refs/workspace-page-objects` for current page objects. Internal `:rect` and `:frame` correspond to Plugin API rectangle and board. Component head and outermost instance root differ for nested instances; see `mem:common/components`.
- Navigate with `app.main.data.common/go-to-workspace`, supplying team, file and page IDs explicitly; omitted values can fall back to the current workspace. `app.main.store/emit!` dispatches an event and returns nil, not the watch result. Helpers in `app.plugins.utils` locate shapes, files and components.
- A store captures `on-event` when created, so replacing the var does not intercept its events. Subscribe to `app.main.store/stream` instead and dispose the subscription. Mutable CLJS hooks can be patched for debugging; restore them or reload afterward. For a temporary RPC spy wrap the target `app.main.repo/cmd!` multimethod's default method and remove the method after use.
- `(.reload js/location)` clears runtime patches and reloads saved state; it can discard unsaved edits. For crash diagnosis and recovery see `mem:frontend/handling-crashes`; for component and change tracing see `mem:common/components`. Development browser-console commands are defined in `frontend/src/debug.cljs`.
