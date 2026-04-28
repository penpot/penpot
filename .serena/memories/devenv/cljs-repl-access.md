# ClojureScript REPL Access via shadow-cljs

## Overview
The penpot frontend uses shadow-cljs with `:target :esm` and multi-module code splitting. The CLJS REPL evaluates code in the browser runtime via a websocket connection.

## Known Pitfall: Rasterizer vs Workspace Runtime
The workspace page embeds a rasterizer iframe (`rasterizer.html`) that also loads the `:main` shadow-cljs build. Both runtimes register with shadow-cljs. If the rasterizer connects first, the REPL will target it instead of the workspace — and the rasterizer has an **empty app state** (its own `defonce` store instance).

**Symptoms:** `@st/state` returns nil, `(.-title js/document)` returns "Penpot - Rasterizer".

**Fix:** Restart the devenv (`docker restart penpot-devenv-main`) and reload the browser. After a clean restart, the workspace runtime typically connects first.

**Verification:** Run `(.-title js/document)` — it should show your file name (e.g. "New File 1 - Penpot"), not "Penpot - Rasterizer".

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
```clojure
(require '[app.main.store :as st])
(some? @st/state)  ;; should be true

;; Get current page id
(:current-page-id @st/state)

;; Get objects on current page
(let [state @st/state
      page-id (:current-page-id state)
      objects (get-in state [:workspace-data :pages-index page-id :objects])]
  (count objects))

;; Get a specific shape
(let [state @st/state
      page-id (:current-page-id state)
      objects (get-in state [:workspace-data :pages-index page-id :objects])
      shape (get objects (parse-uuid "some-uuid-here"))]
  (select-keys shape [:name :type :component-id :component-file :component-root]))
```

## Notes
- nREPL server runs on port 3447 inside the container, mapped to host
- The `:main` build has multiple modules: shared, main, main-workspace, rasterizer, etc.
- `app.main.store/state` is a potok store (wrapping an okulary atom) created via `defonce`
- Ignore the "WARNING: shadow-cljs not installed in project" message — it works via the running server
- Use `timeout` to avoid hanging if the browser is disconnected
- `DO NOT` call `shadow.cljs.devtools.api/repl-runtime-select` with a runtime that can't eval — it will jam the REPL until restart
