# nREPL Eval

Evaluate Clojure (or ClojureScript) code via a running nREPL server using
`scripts/nrepl-eval.mjs` — a standalone CLI application.

Session state (defs, in-ns, etc.) persists across invocations via a stored
session ID, so you can build up state incrementally.

## Usage

```bash
node scripts/nrepl-eval.mjs [options] [<code>]
# or
./scripts/nrepl-eval.mjs [options] [<code>]
```

## Options

| Flag | Description | Default |
|------|-------------|---------|
| `--backend` | Connect to backend nREPL (port 6064) | — |
| `--frontend` | Connect to frontend nREPL (port 3447) | — |
| `-p, --port PORT` | nREPL server port | `6064` |
| `-H, --host HOST` | nREPL server host | `127.0.0.1` |
| `-t, --timeout MS` | Timeout in milliseconds | `120000` |
| `--reset-session` | Discard stored session and start fresh | — |
| `-e, --last-error` | Evaluate `*e` to retrieve the last exception | — |
| `-h, --help` | Show help message | — |

- `--backend` and `--frontend` are mutually exclusive.
- Explicit `--port` is overridden when `--backend`/`--frontend` is used.

## When to Use

1. **Evaluate Clojure code** during development — test functions, inspect
   state, or run experiments against a running Clojure process.
2. **Verify that edited files compile** — require namespaces with `:reload`
   to pick up changes.
3. **Inspect the last exception** after a failed evaluation — use `-e` to
   print the error stored in `*e`.

## Workflow

### Session management

Sessions are persisted to `/tmp/penpot-nrepl-session-<host>-<port>`. State
carries across calls automatically:

```bash
./scripts/nrepl-eval.mjs '(def x 42)'
./scripts/nrepl-eval.mjs 'x'
# => 42
```

Reset the session to start fresh:

```bash
./scripts/nrepl-eval.mjs --reset-session '(def x 0)'
```

### Evaluate code

**Single expression (inline) — uses default port 6064:**
```bash
./scripts/nrepl-eval.mjs '(+ 1 2 3)'
```

**Backend nREPL (explicit):**
```bash
./scripts/nrepl-eval.mjs --backend '(+ 1 2 3)'
```

**Frontend nREPL:**
```bash
./scripts/nrepl-eval.mjs --frontend '(js/alert "hi")'
```

**Multiple expressions via heredoc (recommended — avoids escaping issues):**
```bash
./scripts/nrepl-eval.mjs <<'EOF'
(def x 10)
(+ x 20)
EOF
```

**Override with a different port:**
```bash
./scripts/nrepl-eval.mjs -p 7888 '(+ 1 2 3)'
```

### Inspect last exception

After code throws an error, retrieve the full exception details:

```bash
./scripts/nrepl-eval.mjs -e
```

## Common Patterns

**Require a namespace with reload:**
```bash
./scripts/nrepl-eval.mjs "(require '[my.namespace :as ns] :reload)"
```

**Test a function:**
```bash
./scripts/nrepl-eval.mjs "(ns/my-function arg1 arg2)"
```

**Long-running operation with custom timeout:**
```bash
./scripts/nrepl-eval.mjs -t 300000 "(long-running-fn)"
```

### Accessing Private Functions

Private functions (declared with `^:private` or `defn-`) cannot be called 
directly from outside their namespace. Use the var quote syntax `#'` to 
access the underlying var:

**This fails:**
```bash
./scripts/nrepl-eval.mjs "(app.rpc.commands.error-reports/build-list-query {})"
# => Syntax error: app.rpc.commands.error-reports/build-list-query is not public
```

**This works:**
```bash
./scripts/nrepl-eval.mjs "(#'app.rpc.commands.error-reports/build-list-query {})"
# => Returns the result
```

The `#'` reader macro resolves to `(var ...)`, giving you direct access to 
the var regardless of its visibility modifier. The syntax is `#'` followed 
by the fully qualified symbol.

## Key Principles

- **Default port is 6064** — just pass code directly, no `-p` needed when
  your nREPL server is on 6064. Use `--backend` (6064) or `--frontend` (3447)
  as quick aliases. Use `-p <PORT>` for any other port.
- **Always use `:reload`** when requiring namespaces to pick up file changes.
- **Session is reused** across invocations — defs, in-ns, and var bindings
  persist. Use `--reset-session` to clear.
- **Do not start any server** — the tool connects to an existing nREPL
  server, it is not the agent's responsibility to start the nREPL server
  (assume the server is already running on the specified port).
