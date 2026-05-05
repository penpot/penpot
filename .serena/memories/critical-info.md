You are working on the GitHub project penpot/penpot.

# Working with Penpot Designs via the JavaScript API

Before working with Penpot designs, call the `high_level_overview` tool of the Penpot MCP server.
It explains the API, which you can use to automate tasks via the `execute_code` tool.

# Dev Workflow

Memories:
  - before creating a commit, read `creating-commits`.
  - before creating a PR, read `creating-prs`.

# Frontend

Read the file `frontend/AGENTS.md` for an overview.
Memories:
  - connection between the JavaScript API and the ClojureScript code: `frontend/js-api-to-cljs-binding`.
  - executing ClojureScript code in the frontend: `frontend/cljs-repl`.
  - programmatically navigating to a file in the workspace: `frontend/navigation`.

## Detecting Crashes

The Penpot frontend can crash silently from the JS API's perspective: `execute_code` calls return successfully, but 1-2s later the workspace becomes unusable (Internal Error page). 
The `execute_code` tool then stops working, but `cljs_repl` still works. Use it to detect a crash via `(some? (:exception @app.main.store/state))`.
For details on handling crashes, read memory `frontend/handling-crashes`.
