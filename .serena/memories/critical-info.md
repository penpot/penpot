You are working on the GitHub project `penpot/penpot`, a monorepo.

# Cross-cutting guidance

- Edits/stale refs/duplication cleanup: `mem:memory-maintenance`.
- Cross-cutting testing principles, TDD workflow, and anti-patterns: `mem:testing`.
- Structured logging conventions (`:hint` casing and fields, log levels): `mem:clojure/logging` — read before adding or changing any log call.
- Verified Clojure language behaviors that contradict common assumptions (e.g. `int?` covers `Long`; `integer?` is the general predicate): `mem:clojure/idioms` — read before assuming stdlib predicate semantics.
- Read `mem:clojure/core` (function shape, boundary checks, naming) before writing or modifying Clojure/ClojureScript code — not only when designing a solution or an API.
- Align the values of multi-line Clojure/CLJS `let` bindings to the same column.
- Promises: which call style to use where (`fmap`/`mcat`/`merr` vs `then`/`catch`) and how they bridge to rx: `mem:clojure/promesa` — read before chaining promises or mixing them with observables.

# Development workflow

- Before a repo-wide pnpm version update → `mem:workflow/updating-pnpm` (workspace layout, `scripts/sync-pnpm-version` flow, the stamp-missing-field and ignored-builds gotchas, verification steps)
- Before a Node version update → `mem:workflow/updating-node` (the six pin sites in `.nvmrc`, the devenv and image Dockerfiles and the plugins-deploy workflows, the DHI tag and digest rules, what to verify)
- Before bumping a non-JS dependency pinned in `docker/images/` (JDK, ImageMagick) → `mem:workflow/docker-images`

# Project modules

This is a monorepo. Principles that apply to one module do *not* generally apply to others. Do not make assumptions.

- `frontend/`: ClojureScript + SCSS SPA/design editor; core conventions: `mem:frontend/core`.
- `backend/`: JVM Clojure HTTP/RPC server with PostgreSQL, Redis, storage, mail, and workers; core conventions: `mem:backend/core`. Runtime services and the task-queue vs Pub/Sub topology that constrains horizontal scaling: `mem:prod-infra/core`.
- `common/`: shared CLJC data types, geometry, schemas, file/change logic, and utilities; core conventions: `mem:common/core`.
- `render-wasm/`: Rust -> WebAssembly Skia renderer consumed by frontend; core conventions: `mem:render-wasm/core`.
- `exporter/`: ClojureScript/Node headless Playwright SVG/PDF export; core conventions: `mem:exporter/core`.
- `mcp/`: TypeScript Model Context Protocol integration; core conventions: `mem:mcp/core`.
- `plugins/`: TypeScript plugin runtime/examples and Plugin API types; core conventions: `mem:plugins/core`.
- `library/`: design library workflows; core conventions: `mem:library/core`.
- `docs/`: documentation site; core workflow and conventions: `mem:docs/core`.
- `media-processor/`: TypeScript/Node.js HTTP service for image (sharp) and font (FontForge) processing; core conventions: `mem:media-processor/core`.

# Low-centrality project paths

- `docker/` contains devenv related code, not needed unless specifically instructed. When working on devenv startup, compose layout, instance config (`defaults.env`), tmux session lifecycle, RustFS provisioning, or anything in `manage.sh`'s `*-devenv` commands, read `mem:devenv/core`.
- `experiments/` contains standalone experimental HTML/JS/scripts; treat it as non-core unless the user explicitly asks about it.
- `sample_media/` contains sample image/icon media and config used as fixtures/demo material; do not infer app behavior from it.

# Dev Scripts (scripts/)

- `scripts/nrepl-eval.mjs` — Evaluate Clojure/ClojureScript code via nREPL. Supports `--backend` (port 6064) and `--frontend` (port 3447) aliases. See `mem:scripts/nrepl-eval`.
- `scripts/paren-repair` — Fix mismatched delimiters in Clojure/CLJS and reformat with cljfmt. If edits break delimiters, repair them before lint and format checks. See `mem:scripts/paren-repair`.
- `scripts/psql` — PostgreSQL client wrapper with devenv defaults. Companion: `scripts/db-schema` for DDL dumps. See `mem:scripts/psql`.
- `scripts/taiga.py` — Fetch public issues, user stories, and tasks from the Penpot Taiga project without authentication. See `mem:scripts/taiga`.
- `scripts/gh.py` — GitHub operations helper: list milestone issues, fetch PR details, compare against CHANGES.md, create issues with their Issue Type in one command. Requires `gh` CLI. See `mem:scripts/gh`.
- Before changing coverage summaries or PR comments, read `mem:scripts/coverage-reporting` for metrics, concurrent comment updates, and fork permissions.
- `scripts/project-anomalies.py` — Main-board anomaly check for a milestone (`check` writes `tmp/<MILESTONE>-ANOMALIES.md`). Bulk resolution via `scripts/gh.py`. See `mem:scripts/project-anomalies`.
- `scripts/changelog.py` — Changelog checks for `mem:workflow/updating-changelog`: `boom` (generate the `:boom:` section from `breaking change` issues), `check-merged` (PR merge status), `cross-ref` (milestone PRs vs changelog section), `report` (anomaly report to CHANGES-ISSUES.md). Calls `scripts/gh.py` via subprocess. See `mem:scripts/changelog`.
- `scripts/error-reports.mjs` — Query error reports via RPC API with token authentication. Supports list/get operations with filtering and pagination. See `mem:scripts/error-reports`.
- `scripts/clean-node-modules` — Remove stale `node_modules` from all pnpm workspaces (root, modules, member packages). Keeps the shared pnpm store at `<repo>/.pnpm-store` unless `--store`; ignores `external/` and `.opencode/`. Usage and reinstall steps: `mem:workflow/updating-pnpm`.
- `scripts/setup-opencode` — Install the opencode CLI in-container as a global pnpm package in `$PNPM_HOME`. Not part of the devenv image; see `mem:devenv/core`.
- `scripts/ci` — Per-module lint, tests and format checks. See `mem:scripts/ci` for flags, logs and the backend assert flag.

# Dependency graph

`frontend -> common`, `backend -> common`, `exporter -> common`, and `frontend -> render-wasm`. Changes in `common` can affect frontend, backend, exporter, file migrations, and design-library behavior; validate across consumers when semantics change.

# Working with Penpot designs

- Before automating or inspecting Penpot designs through the Plugin API, call the Penpot MCP `high_level_overview` tool.
- connection between the JavaScript plugin API and the ClojureScript code: `mem:frontend/plugin-api-to-cljs-binding`.
- executing ClojureScript code in the frontend: `mem:frontend/cljs-repl`.
- Failed ClojureScript build or stale hot reload: `mem:frontend/compile-diagnostics`.

- Successful `execute_code` followed by Internal Error: `mem:frontend/handling-crashes` for delayed failures, store inspection and recovery.
