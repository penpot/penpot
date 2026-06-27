# Handling Errors and Debugging

## Finding source errors

You have access to two tools for finding errors in Clojure source code (which you may introduce yourself through edits):

1. cljs_compiler_output
2. clj_check_parentheses

The latter is needed because syntax errors in parentheses give an uninformative compiler error, and the second
tool can often find the exact location of such errors.

When delimiter errors are detected (typically from lint or compiler output),
fix the affected files with `tools/paren-repair.bb`. The `clj_check_parentheses`
MCP tool can also pinpoint the error location when available, but it is not
required — standard build errors are usually enough.
See `mem:tools/paren-repair`.

## Runtime patching with `set!`

Some frontend vars are deliberately mutable escape hatches for runtime instrumentation or circular-dependency patching. 
From `cljs_repl`, use `set!` for temporary debugging of CLJS vars such as 
`app.main.store/on-event`, `app.main.errors/reload-file`, `app.main.errors/is-plugin-error?`, 
`app.main.errors/last-report`, or `app.main.errors/last-exception`. 
These patches affect only the live browser runtime and disappear on reload or recompilation.

```clojure
;; Log non-noisy Potok events temporarily.
(set! app.main.store/on-event
      (fn [event]
        (when (potok.v2.core/event? event)
          (.log js/console (potok.v2.core/repr-event event)))))
```

Restore mutable hooks after debugging, or reload the frontend. Use JVM `alter-var-root` only for JVM Clojure; 
it is not the normal way to patch live CLJS browser vars.

## Browser-console debug namespace

In development, the JS console exposes `debug` helpers from `frontend/src/debug.cljs`:

```javascript
debug.set_logging("namespace", "debug");
debug.dump_state();
debug.dump_buffer();
debug.get_state(":workspace-local :selected");
debug.dump_objects();
debug.dump_object("Rect-1");
debug.dump_selected();
debug.dump_tree(true, true);
```

Visual workspace debug overlays can be toggled with `debug.toggle_debug("bounding-boxes")`, `"group"`, `"events"`, or `"rotation-handler"`; `debug.debug_all()` and `debug.debug_none()` toggle all visual aids.

For temporary source traces, prefer existing logging (`app.common.logging` / `app.util.logging`) or short-lived `prn`, `app.common.pprint/pprint`, `js/console.log`, or `js-debugger` calls. Remove temporary source instrumentation before committing.
