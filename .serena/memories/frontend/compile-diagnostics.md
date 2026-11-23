# Frontend compile diagnostics

- Diagnose the `:main` shadow-cljs build before restarting the app or treating stale behavior as a runtime bug. Read the watch output or use Penpot MCP `cljs_compiler_output` if available. `common/` CLJC changes normally recompile through the same watch.
- A delimiter error can point to a later form or EOF. Repair the affected file before linting: `mem:scripts/paren-repair`. Penpot MCP `clj_check_parentheses`, if available, can help locate the mismatch.
- From `frontend/`, `clojure -M:dev:shadow-cljs compile test` reports analyzer warnings only for namespaces it recompiles. A warm incremental run with no warnings is not evidence that old warnings are gone; use a fresh test build cache to verify the whole target. Treat `:fn-arity` as a defect; see `mem:frontend/testing-unit`.
- If the build succeeds but the browser still fails, use `mem:frontend/cljs-repl` or `mem:frontend/handling-crashes`. For minified stacks, consult the release targets and source-map settings in `frontend/shadow-cljs.edn` and the build commands in `frontend/package.json`.
