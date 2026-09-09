# CI (scripts/ci)

`scripts/ci` runs CI-style checks — lint, tests, format — for one or more
monorepo modules and prints a per-task summary. It is the local equivalent
of CI; use it to verify changes before declaring work done.

## When to use

- After implementing or fixing code in a module: run its checks before
  finishing (AGENTS.md: run the applicable lint and format checks).
- When `common/` changed: validate its consumers too (frontend, backend,
  exporter; see the dependency graph in `mem:critical-info`).
- To fix formatting across a module (`--fix`) or repair delimiters
  (`--paren-repair`) before linting.

## How to use (CLI)

Run from the repo root:

```bash
./scripts/ci MODULE...                  # lint + test + fmt per module
./scripts/ci --all --no-test            # lint + fmt on all modules
./scripts/ci --lint frontend            # lint only
./scripts/ci --fix --no-test frontend   # format files, skip tests
./scripts/ci --paren-repair --all       # fix delimiters in all Clojure modules
./scripts/ci --dry-run --all            # preview what would run
```

Modules: `frontend backend common render-wasm exporter mcp plugins library`.

Flags:

- Default tasks: `lint`, `test`, `fmt` (format check; `--fix` formats
  instead).
- `--lint` / `--test` / `--fmt` run one task only; `--no-lint` /
  `--no-test` / `--no-fmt` drop one task from the default set.
- `--paren-repair` runs only the delimiter repair — it wraps
  `scripts/paren-repair` over each module's Clojure/CLJS sources; see
  `mem:scripts/paren-repair`.
- `--all` selects every module; `--exclude MOD` drops one (repeatable).
- `--fail-fast` stops at the first failure; `--quiet` suppresses failure
  output; `--dry-run` prints commands without running; `--clean` removes
  the log directory.

## Logs and exit codes

- Full output of every task: `.ci-logs/<module>-<task>.log`.
- On failure the script prints the last 30 lines; the final summary lists
  every failed `module:task` with its log path.
- Exit code 0 when all selected tasks passed, 1 otherwise.
- Diagnose failures by reading the log file — never pipe test output
  through filters (AGENTS.md hard rule).

## Notes

- `mcp` has no lint task (shows as skipped). `render-wasm` uses `./lint`,
  `./test`, and `cargo fmt`.
- Test tasks are long-running (backend: `clojure -M:dev:test`); use a
  generous timeout when calling it from an agent shell.
- Skill entry point: `.agents/skills/local-ci/SKILL.md`.
- Testing principles and output discipline: `mem:testing`.
