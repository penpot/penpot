# nREPL eval

- `scripts/nrepl-eval.mjs` connects to an existing nREPL; use `--help` for options. `--backend` and `--frontend` select fixed ports and override `--port`; they cannot be combined. For browser runtime selection and live CLJS inspection see `mem:frontend/cljs-repl`.
- Session IDs are stored under `os.tmpdir()` by host and port, so calls to the same address reuse definitions and namespace state. `--reset-session` removes the locally stored ID before cloning a new session; it does not close the previous server session. Do not assume a session is private to the current shell.
- Inspect the evaluation response, not just the process exit code: the script prints `err` and status messages but does not itself turn every evaluation error response into a nonzero exit. `--last-error` evaluates `*e` in the selected session. Connection and timeout failures do exit nonzero.
- To call a private JVM Clojure var, use its fully qualified var quote, e.g. `(#'my.namespace/private-fn args)`. For changed namespaces, use `require` with `:reload` when appropriate. Do not start another server merely to evaluate code against an existing one.
