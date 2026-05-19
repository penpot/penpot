# Handling Penpot Frontend Crashes

When the Penpot frontend crashes, it usually shows the **Internal Error** page (title text "Something bad happened", class `main_ui_static__download-link`).

A typical error pattern is: Changes go through (JS API, `execute_code`), but about 1-2s later, an `update-file` request hits the backend with the change and gets rejected.
So be sure to check the status for a crash.

After a crash, `execute_code` is unusable (no instances connected), and any data in `storage` is lost, but `cljs_repl` keeps working!

## 1. Detect the crash

cljs REPL `(some? (:exception @app.main.store/state))` returns `true` when the Internal Error page is showing, 
`false` on a healthy workspace (and after a successful reload).

## 2. Read the cause

The exception is stored at `(:exception @app.main.store/state)`. Useful keys:

- `:type`, `:code`, `:status` — error class (e.g. `:validation` / `:referential-integrity` / `400`)
- `:hint`, `:details` — human-readable explanation; `:details` typically contains a vector of validation problems with `:shape-id`, `:page-id`, `:args`, etc.
- `:uri` — the API endpoint that returned the error (e.g. `update-file`)
- `:app.main.errors/instance` — the underlying JS Error object
- `:app.main.errors/trace` — JS stack trace string (only shows the response-handling path, not the dispatch site that produced the bad change)

```
(let [ex (:exception @app.main.store/state)]
  (select-keys ex [:type :code :status :hint :details :uri]))
```

For backend validation errors (`:type :validation`), `:details` is the most informative field — it tells you exactly which shape and which invariant was violated.

## 3. Recover and continue testing

Reload steps:
1. List tabs with `playwright:browser_tabs` (`action: list`) and find the Penpot workspace tab (URL contains `/#/workspace`, title ends in `- Penpot`).
2. If it isn't the current tab, select it via `playwright:browser_tabs` (`action: select`, `index: <n>`). The selected tab's URL then appears as "Page URL" in the result.
3. Reload by calling `playwright:browser_navigate` with that same URL.
4. Confirm recovery: `(some? (:exception @app.main.store/state))` should now return `false`.

Whether the offending change persists depends on the crash type:
For **backend-rejected changes** (e.g. `:type :validation`, 4xx on `update-file`), changes are NOT persisted. Reload restores the pre-crash state — safe to retry.
