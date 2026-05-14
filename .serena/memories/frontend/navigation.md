# Navigating to a File in the Workspace

To programmatically open a file in the workspace, use `cljs_repl` with:
```clojure
(do (require '[app.main.data.common :as dcm])
    (app.main.store/emit! (dcm/go-to-workspace
      :team-id (parse-uuid "<team-id>")
      :file-id (parse-uuid "<file-id>")
      :page-id (parse-uuid "<page-id>"))))
```
**All three IDs are required.** You can get:
- `team-id` from `(:current-team-id @app.main.store/state)`
- `file-id` from the dashboard files: `(vals (:files @app.main.store/state))`
- `page-id` by fetching the file: `(get-in file-data [:data :pages])` via `(rp/cmd! :get-file {:id file-id :features (get @app.main.store/state :features)})`
