# Frontend Compile Diagnostics

Separate from runtime crash recovery.

## First check the shadow-cljs build

Use the Penpot MCP `cljs_compiler_output` tool to inspect the latest shadow-cljs `:main` build status. This is the fastest way to distinguish a bad build from a runtime error in the browser.

Recommended order after CLJ/CLJC/CLJS source edits:
1. Run `cljs_compiler_output`.
2. If the compiler reports a Clojure syntax problem, especially unmatched delimiters or a confusing location, run `clj_check_parentheses` on the absolute path of the suspect `.clj`, `.cljc`, or `.cljs` file.
3. After the build is healthy, use `mem:frontend/cljs-repl`, browser tools, or runtime crash checks for behavior.

## Parentheses checker

`clj_check_parentheses` analyzes one Clojure/ClojureScript source file and reports the area likely responsible for unclosed parentheses/brackets/braces. Use it when compiler output points near EOF, points at a misleading later form, or says delimiter-related syntax errors.

## Hot reload notes

When the frontend shadow-cljs watch process is running, edits to CLJC files in `common/` are normally recompiled into the browser automatically. Do not restart the frontend before checking `cljs_compiler_output`; stale behavior is often a failed build.

For production/minified stack traces, build the production bundle from `frontend/` with `pnpm run build:app`. Output and source maps are generated under `frontend/resources/public/js`; inspect source maps or shadow-cljs reports using build ids from `shadow-cljs.edn`.