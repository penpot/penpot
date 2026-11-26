# nREPL eval

- `scripts/nrepl-eval.mjs` connects to an existing nREPL; use `--help` for options. `--backend` and `--frontend` select fixed ports and override `--port`; they cannot be combined. For browser runtime selection and live CLJS inspection see `mem:frontend/cljs-repl`.
- Session IDs are stored under `os.tmpdir()` by host and port, so calls to the same address reuse definitions and namespace state. `--reset-session` removes the locally stored ID before cloning a new session; it does not close the previous server session. Do not assume a session is private to the current shell. An `unknown-session` response triggers a fresh session and one retry.
- Evaluation errors, connection failures and timeouts exit nonzero. On timeout the client sends an interrupt for the running evaluation; `--last-error` evaluates `*e` in the selected session.
- To call a private JVM Clojure var, use its fully qualified var quote, e.g. `(#'my.namespace/private-fn args)`. For changed namespaces, use `require` with `:reload` when appropriate. Do not start another server merely to evaluate code against an existing one.
- The backend test JVM uses port 6067 by default. Prefer `backend/scripts/test-repl test` or `eval` for its startup and lock; use the raw port for other evaluations. Never call `(restart)` or `(restart-all)` there. See `mem:backend/testing`.
