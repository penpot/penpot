# ClojureScript REPL Access via shadow-cljs

Execute code in the REPL via the Penpot MCP's `cljs_repl` tool.

## Accessing App State

The main store is `app.main.store/state`. It contains workspace metadata, selection, UI state, etc.
However, **page objects are NOT in the main store atom**. They live behind derived refs.

### Top-level store keys (subset)
`:current-page-id`, `:current-file-id`, `:workspace-local`, `:workspace-global`,
`:workspace-trimmed-page`, `:workspace-undo`, `:workspace-guides`, `:workspace-layout`,
`:workspace-presence`, `:workspace-ready`, `:profile`, `:route`, etc.

**Notable absence:** There is no `:workspace-data` key in the store. The old path
`(get-in state [:workspace-data :pages-index page-id :objects])` does NOT work.

### Getting page objects — use `app.main.refs/workspace-page-objects`
```clojure
;; This is a derived ref (reactive lens). Deref it directly:
(let [objects @app.main.refs/workspace-page-objects
      shape (get objects (parse-uuid "some-uuid-here"))]
  (select-keys shape [:name :type :x :y :width :height :fills :strokes :rotation :opacity :frame-id :parent-id]))
```

### Getting the current selection
```clojure
;; Selection is in the main store under :workspace-local :selected
(let [state @app.main.store/state
      selected (get-in state [:workspace-local :selected])]
  (mapv str selected))
;; Returns vector of UUID strings for selected shapes
```

### Other useful store access
```clojure
;; Current page id
(:current-page-id @app.main.store/state)

;; Verify state is accessible
(some? @app.main.store/state)  ;; should be true

;; workspace-local keys: :zoom :selected :hide-toolbar :last-selected :vbox
;;   :highlighted :vport :expanded :selrect :zoom-inverse
```

### Shape data structure (internal ClojureScript representation)
Shape keys use kebab-case keywords (`:fill-color`, `:fill-opacity`, `:parent-id`, `:frame-id`).
The shape `:type` is a keyword like `:rect`, `:path`, `:text`, `:ellipse`, `:image`, `:bool`, `:svg-raw`, `:frame`, `:group`.
Note `:rect` in CLJS corresponds to "rectangle" in the JS Plugin API, and `:frame` corresponds to "board".

## Notes
- The `:main` build has multiple modules: shared, main, main-workspace, rasterizer, etc.
- `app.main.store/state` is a potok store (wrapping an okulary atom) created via `defonce`
- Use `timeout` to avoid hanging if the browser is disconnected

## Troubleshooting

`cljs_repl` may not connect to the right runtime when several are attached (e.g. workspace tab + rasterizer). Verify with `(.-title js/document)` — it should show your file name, not "Penpot - Rasterizer".

To list runtimes or target one by client-id, use `npx shadow-cljs clj-eval` from `/home/penpot/penpot/frontend`. It talks to the shadow-cljs JVM process, so unlike `cljs_repl` it has access to `shadow.cljs.devtools.api`:

```bash
printf '(shadow.cljs.devtools.api/repl-runtimes :main)\n' | timeout 10 npx shadow-cljs clj-eval --stdin
printf '(shadow.cljs.devtools.api/cljs-eval :main "<cljs-code>" {:client-id 5})\n' | timeout 10 npx shadow-cljs clj-eval --stdin
```