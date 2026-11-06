# CI (scripts/ci)

`scripts/ci` runs CI-style checks — lint, tests, format — and, on request,
the production build of each module, for one or more monorepo modules, and
prints a per-task summary. It is the local equivalent of CI; use it to
verify changes before declaring work done.

It also exports `PENPOT_FLAGS` with `enable-backend-asserts` appended (keeping
whatever the caller already set), which is what puts `clojure.core/assert`
into the compiled test code. Prefer it over calling `clojure -M:dev:test` by
hand for that reason alone: without the flag the suite passes while every
assertion is compiled out. GitHub Actions sets the same flag.

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
./scripts/ci --build MODULE             # production build only
./scripts/ci --all --no-test            # lint + fmt on all modules
./scripts/ci --lint frontend            # lint only
./scripts/ci --fix --no-test frontend   # format files, skip tests
./scripts/ci --paren-repair --all       # fix delimiters in all Clojure modules
./scripts/ci --dry-run --all            # preview what would run
```

Modules: `frontend backend common render-wasm exporter mcp plugins library
media-processor`.

Flags:

- Default tasks: `lint`, `test`, `fmt` (format check; `--fix` formats
  instead). Build is **not** in the default set.
- `--lint` / `--test` / `--fmt` / `--build` run one task only; `--no-lint` /
  `--no-test` / `--no-fmt` / `--no-build` drop one task from the default set.
- `--paren-repair` runs only the delimiter repair — it wraps
  `scripts/paren-repair` over each module's Clojure/CLJS sources; see
  `mem:scripts/paren-repair`.
- `--all` selects every module; `--exclude MOD` drops one (repeatable).
- `--fail-fast` stops at the first failure; `--quiet` suppresses failure
  output; `--dry-run` prints commands without running; `--clean` removes
  the log directory.

## Build task

`--build` compiles each module the way production does, reusing caches
(`.shadow-cljs`, `target/`, `node_modules`) instead of clearing them. It
does not package bundles: `manage.sh build-bundle` remains the way to
produce deployable artifacts.

| Module | Command | Output |
|---|---|---|
| `frontend` | `NODE_ENV=production pnpm run build:app:main && pnpm run build:app:libs && pnpm run build:app:assets` | `frontend/resources/public/`, `frontend/target/` |
| `backend` | `clojure -T:build jar` | `backend/target/penpot.jar` |
| `common` | *(none; skipped)* | — |
| `render-wasm` | `./build` (targets `frontend` and `export`) | `render-wasm/target/` |
| `exporter` | `NODE_ENV=production pnpm run build:app` | `exporter/target/` |
| `mcp` | `pnpm run build` | `mcp/*/dist` |
| `plugins` | `pnpm run build:runtime && pnpm run build:plugins && pnpm run build:styles-example && pnpm run build:doc` | `plugins/dist/` |
| `library` | `NODE_ENV=production clojure -M:dev:shadow-cljs release library` | `library/target/library/` |
| `media-processor` | `pnpm run types:check && pnpm run build` | `media-processor/dist/` |

- Every output path above is gitignored, so a build leaves `git status`
  clean.
- The module `build` scripts in each `scripts/build` are **not** used: they
  delete `node_modules`, wipe `resources/public` and run `pnpm install`,
  which belongs to the bundle pipeline, not to a local check.
- `frontend` and `library` have a `build` script that clears the shadow-cljs
  cache first; the task calls the inner commands to stay incremental.
  `exporter` uses `build:app` for the same reason.
- `common` has no artifact of its own: its compilation is exercised by its
  `test` task and by the builds of `frontend`, `backend` and `exporter`.
- `media-processor` is the ninth module. It has no lint, so that task shows
  as skipped; its build runs `tsc --noEmit` before esbuild because esbuild
  does not type-check.
- Cold-cache cost measured on the devenv: `library` ~120 s, `frontend` ~120 s,
  `exporter` ~80 s, `backend` ~35 s. With warm caches the whole set (8
  builds) takes ~4 min. The first run of `clojure -T:build jar` downloads
  `tools.build` if it is not in `~/.m2`.
- `pnpm run` may trigger a dependency check/install when `node_modules`
  drifts from the lockfile (`verify-deps-before-run`), so the first run
  after a dependency change can need network.
- `render-wasm/build` takes a lock (`_build_lock`): never run two builds at
  once in the same checkout.


## Logs and exit codes

- Full output of every task: `.ci-logs/<module>-<task>.log`.
- On failure the script prints the last 30 lines; the final summary lists
  every failed `module:task` with its log path.
- Exit code 0 when all selected tasks passed, 1 otherwise.
- Diagnose failures by reading the log file — never pipe test output
  through filters (AGENTS.md hard rule).

## Notes

- `mcp` has no lint task and `media-processor` has no lint task; both show
  as skipped. `render-wasm` uses `./lint`, `./test`, and `cargo fmt`.
- `scripts/test_ci.py` (unittest, `python3 scripts/test_ci.py`) covers the
  flag wiring, one build command per module and the help text through
  `--dry-run`; it executes no real build.
- Exports `PENPOT_FLAGS` with `enable-backend-asserts` appended to any
  existing value, so backend test runs compile in and exercise
  precondition asserts; see `mem:backend/testing`.
- Test and build tasks are long-running (backend: `clojure -M:dev:test`;
  frontend build: ~2 min warm); use a generous timeout when calling it from
  an agent shell.
- Skill entry point: `.agents/skills/local-ci/SKILL.md`.
- Testing principles and output discipline: `mem:testing`.
