---
name: nrepl-eval
description: Evaluate Clojure code via nREPL using the standalone tools/nrepl-eval.mjs CLI tool.
---

# nREPL Eval

Evaluate Clojure (or ClojureScript) code via a running nREPL server using
`tools/nrepl-eval.mjs` — a standalone CLI application.

Session state (defs, in-ns, etc.) persists across invocations via a stored
session ID, so you can build up state incrementally.

## Usage

```bash
node tools/nrepl-eval.mjs [options] [<code>]
```

The tool is also executable directly:
```bash
./tools/nrepl-eval.mjs [options] [<code>]
```

## Options

| Flag | Description | Default |
|------|-------------|---------|
| `-p, --port PORT` | nREPL server port | `6064` |
| `-H, --host HOST` | nREPL server host | `127.0.0.1` |
| `-t, --timeout MS` | Timeout in milliseconds | `120000` |
| `--reset-session` | Discard stored session and start fresh | — |
| `-e, --last-error` | Evaluate `*e` to retrieve the last exception | — |
| `-h, --help` | Show help message | — |

## When to Use

Use this tool when you need to:

1. **Evaluate Clojure code** during development — test functions, inspect
   state, or run experiments against a running Clojure process.
2. **Verify that edited files compile** — require namespaces with `:reload`
   to pick up changes.
3. **Inspect the last exception** after a failed evaluation — use `-e` to
   print the error stored in `*e`.

## Workflow

### 1. Session management

Sessions are persisted to `/tmp/penpot-nrepl-session-<host>-<port>`. State
carries across calls automatically:

```bash
./tools/nrepl-eval.mjs '(def x 42)'
./tools/nrepl-eval.mjs 'x'
# => 42
```

Reset the session to start fresh:

```bash
./tools/nrepl-eval.mjs --reset-session '(def x 0)'
```

### 2. Evaluate code

**Single expression (inline) — uses default port 6064:**
```bash
./tools/nrepl-eval.mjs '(+ 1 2 3)'
```

**Multiple expressions via heredoc (recommended — avoids escaping issues):**
```bash
./tools/nrepl-eval.mjs <<'EOF'
(def x 10)
(+ x 20)
EOF
```

**Override with a different port:**
```bash
./tools/nrepl-eval.mjs -p 7888 '(+ 1 2 3)'
```

### 3. Inspect last exception

After code throws an error, retrieve the full exception details:

```bash
./tools/nrepl-eval.mjs -e
```

## Common Patterns

**Require a namespace with reload:**
```bash
./tools/nrepl-eval.mjs "(require '[my.namespace :as ns] :reload)"
```

**Test a function:**
```bash
./tools/nrepl-eval.mjs "(ns/my-function arg1 arg2)"
```

**Long-running operation with custom timeout:**
```bash
./tools/nrepl-eval.mjs -t 300000 "(long-running-fn)"
```

## Key Principles

- **Default port is 6064** — just pass code directly, no `-p` needed when
  your nREPL server is on 6064. Use `-p <PORT>` for a different port.
- **Always use `:reload`** when requiring namespaces to pick up file changes.
- **Session is reused** across invocations — defs, in-ns, and var bindings
  persist. Use `--reset-session` to clear.
- **Do not start any server** — the tool connects to an existing nREPL
  server, it is not the agent's responsibility to start the nREPL server
  (assume the server is already running on the specified port).
