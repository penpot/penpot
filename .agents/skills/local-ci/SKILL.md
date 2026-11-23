---
name: local-ci
description: Run local CI-style checks with ./scripts/ci (lint, tests, format, and on request production builds) per monorepo module. Use when verifying changes before declaring work done, running lint or tests locally, fixing formatting, repairing Clojure delimiter errors, or checking that a module still compiles.
---

# Local CI

## When to use

Use after changing code or when asked to run checks. Skip while exploring or
planning. If `common` changed, include its affected consumers (see `mem:critical-info`).

## Workflow

1. Read `mem:scripts/ci` for flags, module-specific commands, and exceptions.
   If Clojure or ClojureScript delimiters are broken, repair them before lint.
2. From the repo root, run `./scripts/ci MODULE...` to check lint, tests, and
   formatting. Use a single-task flag during iteration, not as a substitute
   for final verification. Production builds run only on request.
3. Check the exit code and summary. For failures, read
   `.ci-logs/<module>-<task>.log`; never pipe test output directly to filters.
   Give long runs a generous shell timeout or run them in the background.
