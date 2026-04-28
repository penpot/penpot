# ClojureScript REPL Access via shadow-cljs

## Overview
The penpot frontend uses shadow-cljs with `:target :esm` and multi-module code splitting. The CLJS REPL evaluates code in the browser runtime via a websocket connection.

## Method 1: Interactive REPL (inside container)
```bash
docker exec -it penpot-devenv-main bash
cd /home/penpot/penpot/frontend
npx shadow-cljs cljs-repl main
```
Requires an active browser session with penpot open. Type `:cljs/quit` to exit.

## Method 2: Scriptable eval via clj-eval (preferred for automation)
```bash
docker exec penpot-devenv-main bash -c "cd /home/penpot/penpot/frontend && \
  printf '<CLJ_EXPRESSION>\n' | timeout 10 npx shadow-cljs clj-eval --stdin 2>&1"
```

For CLJS evaluation, wrap in `shadow.cljs.devtools.api/cljs-eval`:
```bash
docker exec penpot-devenv-main bash -c "cd /home/penpot/penpot/frontend && \
  printf '(shadow.cljs.devtools.api/cljs-eval :main \"<CLJS_CODE>\" {})\n' | \
  timeout 10 npx shadow-cljs clj-eval --stdin 2>&1"
```

Return format: `{:results ["<result1>" ...] :out "" :err "" :ns cljs.user}`

You can target a specific runtime by client-id:
```
(shadow.cljs.devtools.api/cljs-eval :main "<code>" {:client-id 5})
```

To list connected runtimes and their client-ids:
```
(shadow.cljs.devtools.api/repl-runtimes :main)
```

## Method 3: nREPL client (tools/nrepl_eval.py)
A custom Python nREPL client exists at `tools/nrepl_eval.py`. However, it uses `(shadow/repl :main)` to switch to CLJS mode, which doesn't reliably select the correct runtime. **Prefer Method 2 for automation.**

## Accessing App State

The main store is `app.main.store/state`. It contains workspace metadata, selection, UI state, etc.
However, **page objects are NOT in the main store atom**. They live behind derived refs.

### Top-level store keys (subset)
`:current-page-id`, `:current-file-id`, `:workspace-local`, `:workspace-global`,
`:workspace-trimmed-page`, `:workspace-undo`, `:workspace-guides`, `:workspace-layout`,
`:workspace-drawing`, `:workspace-presence`, `:workspace-ready`, `:profile`, `:route`, etc.

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
- nREPL server runs on port 3447 inside the container, mapped to host
- The `:main` build has multiple modules: shared, main, main-workspace, rasterizer, etc.
- `app.main.store/state` is a potok store (wrapping an okulary atom) created via `defonce`
- Ignore the "WARNING: shadow-cljs not installed in project" message — it works via the running server
- Use `timeout` to avoid hanging if the browser is disconnected
- `DO NOT` call `shadow.cljs.devtools.api/repl-runtime-select` with a runtime that can't eval — it will jam the REPL until restart

## Troubleshooting

The REPL may occasionally not connect to the right runtime.
Run `(.-title js/document)` to verify — it should show your file name (e.g. "New File 1 - Penpot"), not "Penpot - Rasterizer".
