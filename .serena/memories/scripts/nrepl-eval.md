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
| `-t, --timeout MS` | Timeout in milliseconds; the evaluation is interrupted | `120000` |
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
4. **Reach the backend test JVM** on port 6067, for code the wrappers do not
   expose (see "Backend test JVM" below).

## Workflow

### Session management

Sessions are persisted to `/tmp/penpot-nrepl-session-<host>-<port>`. State
carries across calls automatically:

```bash
./scripts/nrepl-eval.mjs '(def x 42)'
./scripts/nrepl-eval.mjs 'x'
# => 42
```

A session dies with the JVM that owns it. The client detects the
`unknown-session` status, discards the stored id, clones a new session and
reruns the code once, so a restarted JVM needs no manual repair.

Discard a live session on purpose (stale defs, the wrong `in-ns`):

```bash
./scripts/nrepl-eval.mjs --reset-session '(def x 0)'
```

### Exit status

- `0`: the code evaluated.
- `1`: the code threw, the call timed out, the server was unreachable, or the
  arguments are invalid.

Closing the connection does not stop work on the server, so a timeout sends an
`interrupt` for the running evaluation first and reports it once the server
acknowledges (message ends with `(interrupted)`). `-e` evaluates `*e`, the last
exception the session caught.

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

## Backend test JVM (port 6067)

`backend/scripts/test-repl` keeps an nREPL server on port 6067 in the devenv
container, on the backend test classpath with `PENPOT_FLAGS=enable-backend-asserts`.
From inside the container, `-p 6067` reaches it:

```bash
docker exec -u penpot -w /home/penpot/penpot penpot-devenv-ws0-main \
  scripts/nrepl-eval.mjs -p 6067 "(user/run-tests! 'backend-tests.my-ns-test)"
```

Prefer `backend/scripts/test-repl test` or `eval`, which start the JVM on
demand, take the per-workspace lock and normalise the exit codes; the raw port
is for code those wrappers do not expose. See `mem:backend/testing` for the
test JVM's own rules.

## Key Principles

- **Default port is 6064** — just pass code directly, no `-p` needed when
  your nREPL server is on 6064. Use `--backend` (6064) or `--frontend` (3447)
  as quick aliases. Use `-p <PORT>` for any other port.
- **Always use `:reload`** when requiring namespaces to pick up file changes.
- **Session is reused:** defs, in-ns, and var bindings persist across
  invocations. A dead session heals itself (new clone, one retry), so
  `--reset-session` is for discarding state deliberately.
- **A failed evaluation exits 1:** test runners and shell scripts can rely on
  it. A timeout interrupts the evaluation instead of leaving it running.
- **Never restart through the client:** `(restart-all)` on the live backend
  restarts the server the client is talking to, and no reply arrives. In the
  test JVM, `(restart)` and `(restart-all)` start a dev backend inside it.
- **Do not start the dev nREPL server:** the tool connects to an existing
  nREPL server on the given port. The one JVM it may assume is the test JVM
  that `backend/scripts/test-repl` starts (6067).
