---
name: nrepl-eval
description: Evaluate Clojure code via nREPL using the standalone scripts/nrepl-eval.mjs CLI tool.
---

# nREPL Eval

Evaluate Clojure (or ClojureScript) code via a running nREPL server using
`scripts/nrepl-eval.mjs`.

Full documentation: `mem:scripts/nrepl-eval` (file: `.serena/memories/scripts/nrepl-eval.md`)

## Quick Reference

```bash
./scripts/nrepl-eval.mjs [options] [<code>]
```

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

## Examples

```bash
./scripts/nrepl-eval.mjs '(+ 1 2 3)'
./scripts/nrepl-eval.mjs --backend '(+ 1 2 3)'
./scripts/nrepl-eval.mjs --frontend '(js/alert "hi")'
./scripts/nrepl-eval.mjs -e
```
