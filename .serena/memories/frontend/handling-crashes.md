# Frontend Runtime Crash Handling

## Detect a runtime workspace crash

Runtime crashes usually show the Internal Error page with title text "Something bad happened" and class `main_ui_static__download-link`. A common pattern is: changes go through via JS API / `execute_code`, then 1-2s later an `update-file` request reaches the backend and is rejected.

After a crash, `execute_code` can become unusable because no plugin instances are connected and any data in its `storage` is lost, but `cljs_repl` usually still works.

Check crash state:

```clojure
(some? (:exception @app.main.store/state))
```

It returns `true` when the Internal Error page is showing and `false` on a healthy workspace or after a successful reload.

## Read the runtime cause

The exception is stored at `(:exception @app.main.store/state)`. Useful keys:

- `:type`, `:code`, `:status`: error class, e.g. `:validation`, `:referential-integrity`, `400`.
- `:hint`, `:details`: human-readable explanation; `:details` often contains validation problems with `:shape-id`, `:page-id`, `:args`, etc.
- `:uri`: API endpoint that returned the error, e.g. `update-file`.
- `:app.main.errors/instance`: underlying JS Error object.
- `:app.main.errors/trace`: JS stack trace string, usually response-handling path rather than the dispatch site that produced the bad change.

```clojure
(let [ex (:exception @app.main.store/state)]
  (select-keys ex [:type :code :status :hint :details :uri]))
```

For backend validation errors (`:type :validation`), `:details` is usually the most informative field; it identifies the shape and invariant that failed.

## Recover and continue testing

Reload steps:
1. List tabs with `playwright:browser_tabs` (`action: list`) and find the Penpot workspace tab (URL contains `/#/workspace`, title ends in `- Penpot`).
2. If it is not current, select it via `playwright:browser_tabs` (`action: select`, `index: <n>`).
3. Reload by calling `playwright:browser_navigate` with that same URL.
4. Confirm recovery: `(some? (:exception @app.main.store/state))` should return `false`.

For backend-rejected changes, such as validation errors on `update-file`, changes are not persisted. Reload restores the pre-crash state, so it is safe to retry after fixing the cause.