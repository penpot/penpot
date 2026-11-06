---
name: local-ci
description: Run local CI-style checks with ./scripts/ci (lint, tests, format, and on request production builds) per monorepo module. Use when verifying changes before declaring work done, running lint or tests locally, fixing formatting, repairing Clojure delimiter errors, or checking that a module still compiles.
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
`plugins` `library` `media-processor`, or `--all` for every module.

With no task flags it runs three tasks per module, in order: **lint**,
**test**, **fmt** (format check; `--fix` formats files instead). **build**
is opt-in: it compiles the module the way production does and is the slow
one, so it never runs unless you ask for it.

| Flag | Effect |
|------|--------|
| `--all` | Run every module |
| `--exclude MOD` | Skip one module (repeatable) |
| `--lint` / `--no-lint` | Run only lint / drop lint |
| `--test` / `--no-test` | Run only tests / drop tests |
| `--fmt` / `--no-fmt` | Run only format check / drop it |
| `--build` / `--no-build` | Run only the production build / drop it |
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

# Check that the module still compiles (slow: minutes)
./scripts/ci --build frontend

# Compile every module (8 builds, ~4 min with warm caches)
./scripts/ci --build --all

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
- `mcp` and `media-processor` have no lint task — they show as skipped,
  not failed. `common` has no build task for the same reason.
- Build runs are the slow ones (frontend ~2 min even with warm caches;
  cold caches roughly double it). Give the shell a generous timeout and
  do not run two builds at once in the same checkout: `render-wasm` takes
  a build lock.
- `--paren-repair` only fixes delimiters; run lint afterwards to catch
  what remains. See `mem:scripts/paren-repair`.
- What to run and how to read test results: `mem:testing`.
