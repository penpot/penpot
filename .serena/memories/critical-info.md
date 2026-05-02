You are working on the GitHub project penpot/penpot.

# Working with Penpot Designs

Before working with Penpot designs, call the `high_level_overview` tool of the Penpot MCP server.
It explains the JavaScript API, which you can use to automate tasks via the `execute_code` tool.

# Critical Memories

* Before creating a commit, read `creating-commits`.
* When working on the Penpot frontend ...
  - read the file `frontend/AGENTS.md` for an overview 
  - to understand the connection between the JavaScript API and the ClojureScript code, read memory `frontend/js-api-to-cljs-binding`.
  - to understand how to execute ClojureScript code in the Penpot frontend, read memory `frontend/cljs-repl`.

# Detecting Frontend Crashes

The Penpot frontend can crash silently from the JS API's perspective: `execute_code` calls return successfully, but 1-2s later the workspace becomes unusable (Internal Error page). 
The `execute_code` tool then stops working, but `cljs_repl` still works. Use it to detect a crash via `(some? (:exception @app.main.store/state))`.
For details on handling crashes, read memory `frontend/handling-crashes`.

