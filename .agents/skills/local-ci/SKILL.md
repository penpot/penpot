---
name: local-ci
description: Run local CI-style checks with ./scripts/ci (lint, tests, format) per monorepo module. Use when verifying changes before declaring work done, running lint or tests locally, fixing formatting, or repairing Clojure delimiter errors.
---

# Local CI

Run the same checks CI runs, locally, for the modules you touched, with
`scripts/ci`. Each task writes a log file; the final summary says what
passed and what failed.

Full details: `mem:scripts/ci` (file: `.serena/memories/scripts/ci.md`)

## When to use

- After implementing or fixing code — verify every module you touched
  before declaring the work done.
- When the user asks to run CI, lint, tests, or format checks locally.
- When you changed `common/` — validate its consumers too.

**Skip:** while exploring, planning, or reading code.

## Command reference

Run from the repo root:

```bash
./scripts/ci [OPTIONS] [MODULES...]
```

Modules: `frontend` `backend` `common` `render-wasm` `exporter` `mcp`
`plugins` `library`, or `--all` for every module.

With no task flags it runs three tasks per module, in order: **lint**,
**test**, **fmt** (format check; `--fix` formats files instead).

| Flag | Effect |
|------|--------|
| `--all` | Run every module |
| `--exclude MOD` | Skip one module (repeatable) |
| `--lint` / `--no-lint` | Run only lint / drop lint |
| `--test` / `--no-test` | Run only tests / drop tests |
| `--fmt` / `--no-fmt` | Run only format check / drop it |
| `--fix` | Format files instead of checking (other tasks unaffected) |
| `--paren-repair` | Fix delimiter errors in Clojure/CLJS files |
| `--fail-fast` | Stop at the first failure |
| `--quiet` | Suppress failure output |
| `--dry-run` | Show what would run, execute nothing |
| `--clean` | Delete the `.ci-logs/` directory |

## Reading failures

Every task writes its full output to `.ci-logs/<module>-<task>.log`. On
failure the script prints only the last 30 lines. To diagnose a failure,
**read the log file** — never re-run the command piped through filters
(repo rule: redirect to a file first, then read it). The exit code is 1
when any task failed; the summary lists each failed `module:task` and its
log path.

## Typical workflows

```bash
# Verify a module you changed: lint + tests + format check
./scripts/ci frontend

# Fast pass while iterating: lint only
./scripts/ci --lint frontend

# Lint + format check, skip the long test suite
./scripts/ci --no-test frontend

# Format the module without running the test suite
./scripts/ci --fix --no-test frontend

# Broke delimiters in Clojure/CLJS files: repair first, then lint
./scripts/ci --paren-repair frontend
./scripts/ci --lint frontend

# Changed common/ — validate its consumers too
./scripts/ci frontend backend exporter

# Preview what would run, without running it
./scripts/ci --dry-run --all
```

## Gotchas

- Run from the repo root.
- Test tasks are long-running (backend runs `clojure -M:dev:test`); give
  the bash call a generous timeout (10–20 minutes) instead of letting it
  time out mid-run.
- `mcp` has no lint task — it shows as skipped, not failed.
- `--paren-repair` only fixes delimiters; run lint afterwards to catch
  what remains. See `mem:scripts/paren-repair`.
- What to run and how to read test results: `mem:testing`.
