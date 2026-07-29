# ClojureScript REPL and Frontend Debugging

Execute code in the live frontend via the Penpot MCP `cljs_repl` tool. For browser-console debugging, the frontend also exports a `debug` JS namespace in development builds.

## Accessing app state

The main store is `app.main.store/state`. It contains workspace metadata, selection, UI state, profile, route, etc. Page objects are not under a `:workspace-data` key; use derived refs.

```clojure
;; Current selection
(mapv str (get-in @app.main.store/state [:workspace-local :selected]))

;; Current page objects
(let [objects @app.main.refs/workspace-page-objects
      shape (get objects (parse-uuid "some-uuid-here"))]
  (select-keys shape [:name :type :x :y :width :height :fills :strokes :rotation :opacity :frame-id :parent-id]))
```

Shape keys use kebab-case keywords. Internal `:rect` corresponds to "rectangle" in the JS Plugin API, and `:frame` corresponds to "board".

Component instance shapes carry `:component-id` and `:component-file` directly; `:component-root` flags the root of an instance. Use `app.common.types.container/get-head-shape` for nearest head and `get-instance-root` for outermost root; they differ for nested instances.

## Navigation recipe

To programmatically open a workspace file, all three ids are required:

```clojure
(do (require '[app.main.data.common :as dcm])
    (app.main.store/emit! (dcm/go-to-workspace
      :team-id (parse-uuid "<team-id>")
      :file-id (parse-uuid "<file-id>")
      :page-id (parse-uuid "<page-id>"))))
```

Get `team-id` from `(:current-team-id @app.main.store/state)`. Get file ids from `(vals (:files @app.main.store/state))`. Get page ids by fetching file data, e.g. through `rp/cmd! :get-file` with current features.

## Reload the live runtime

`(.reload js/location)` (alias `app.util.dom/reload-current-window`) from `cljs_repl` reloads the browser page: clears `set!` runtime patches, re-fetches file state, and is the simplest crash recovery while the repl is live (`mem:frontend/handling-crashes`). To re-fetch only the current file's data without a full page reload, emit `(app.main.store/emit! (potok.v2.core/event :app.main.data.workspace/reload-current-file))`.

## Useful lookup helpers

`app.plugins.utils` contains state lookup helpers that are useful from any CLJS, despite living under `plugins/`:

- `locate-shape`, `locate-objects`, `locate-file`.
- `locate-component` resolves through the outermost instance root.
- `locate-head-component` resolves through the nearest component head.
- `locate-library-component` does direct file-id/component-id lookup.

## Runtime patching with `set!`

Some frontend vars are deliberately mutable escape hatches for runtime instrumentation or circular-dependency patching. From `cljs_repl`, use `set!` for temporary debugging of CLJS vars such as `app.main.store/on-event`, `app.main.errors/reload-file`, `app.main.errors/is-plugin-error?`, `app.main.errors/last-report`, or `app.main.errors/last-exception`. These patches affect only the live browser runtime and disappear on reload or recompilation.

```clojure
;; Log non-noisy Potok events temporarily.
(set! app.main.store/on-event
      (fn [event]
        (when (potok.v2.core/event? event)
          (.log js/console (potok.v2.core/repr-event event)))))
```

Restore mutable hooks after debugging, or reload the frontend. Use JVM `alter-var-root` only for JVM Clojure; it is not the normal way to patch live CLJS browser vars.

## Browser-console debug namespace

In development, the JS console exposes `debug` helpers from `frontend/src/debug.cljs`:

```javascript
debug.set_logging("namespace", "debug");
debug.dump_state();
debug.dump_buffer();
debug.get_state(":workspace-local :selected");
debug.dump_objects();
debug.dump_object("Rect-1");
debug.dump_selected();
debug.dump_tree(true, true);
```

Visual workspace debug overlays can be toggled with `debug.toggle_debug("bounding-boxes")`, `"group"`, `"events"`, or `"rotation-handler"`; `debug.debug_all()` and `debug.debug_none()` toggle all visual aids.

For temporary source traces, prefer existing logging (`app.common.logging` / `app.util.logging`) or short-lived `prn`, `app.common.pprint/pprint`, `js/console.log`, or `js-debugger` calls. Remove temporary source instrumentation before committing.

## Runtime targeting

`cljs_repl` may connect to the wrong runtime when several are attached, such as workspace plus rasterizer. Verify with `(.-title js/document)`; it should show the workspace file name, not "Penpot - Rasterizer".

To list or target shadow-cljs runtimes, run from `/home/penpot/penpot/frontend`:

```bash
printf '(shadow.cljs.devtools.api/repl-runtimes :main)\n' | timeout 10 npx shadow-cljs clj-eval --stdin
printf '(shadow.cljs.devtools.api/cljs-eval :main "<cljs-code>" {:client-id 5})\n' | timeout 10 npx shadow-cljs clj-eval --stdin
```

Use command timeouts so a disconnected browser does not hang the session.