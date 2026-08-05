You are working on the GitHub project `penpot/penpot`, a monorepo.

# Memory system

- Memories are the primary project guidance (not docs or other readme files).
- A section's top-level memory is `<section>/core`. When a section is relevant, read the core memory
   before focused memories.
- Edits/stale refs/duplication cleanup: `mem:memory-maintenance`.
- Cross-cutting testing principles, TDD workflow, and anti-patterns: `mem:testing`.

# Development workflow

- Commit/PR/issue creation is **on explicit request only**. Before any of these actions, read the relevant memory — don't infer format from prior examples:
  - Before `git commit` → `mem:workflow/creating-commits` (subject format, body, `AI-assisted-by: model-name` trailer)
  - Before `gh issue create` → `mem:workflow/creating-issues` (title derivation, body template, labels, Issue Type)
  - Before `gh pr create` / `gh pr edit` → `mem:workflow/creating-prs` (title format, body structure, "Note:" line)
- **Never `git push`, force-push, or modify `git origin`** (or any other remote). The user pushes from their own shell; if a push is required, say so and wait. Never amend a commit that the user has already pushed unless explicitly asked.
- You have access to the GitHub CLI `gh` or corresponding MCP tools.
- Issues are also managed on Taiga. Read issues using the `read_taiga_issue` tool.
- Before writing code, analyze the task in depth and describe your plan. If the task is complex, break it down into atomic steps.
  *After making changes, run the applicable lint and format checks for the affected module before considering the work done (per example `mem:backend/core` or `mem:frontend/core`).
- Align `let` binding values: when a `let` form has multiple bindings spanning
  several lines, align the value forms to the same column with spaces.
- If you introduce delimiter errors (mismatched parens/brackets) in Clojure/CLJS files,
  fix them with `scripts/paren-repair` BEFORE running lint/format checks.
  See `mem:scripts/paren-repair` for usage.
- Never run anything that destroys data without explicit permission, including `drop-devenv`, `docker compose down -v`, `docker volume rm ...`. The user's real work lives in the volumes of the shared infra.

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

The memory is structured in a way that you can get the critical information about the
module. You can read it from `mem:<MODULE>/core`

# Low-centrality project paths

- `docker/` contains devenv related code, not needed unless specifically instructed.
   When working on devenv startup, compose layout, instance config (`defaults.env`),
   tmux session lifecycle, MinIO provisioning, or anything in `manage.sh`'s
   `*-devenv` commands, read `mem:devenv/core`.
- `experiments/` contains standalone experimental HTML/JS/scripts; treat it as non-core unless the user explicitly asks about it.
- `sample_media/` contains sample image/icon media and config used as fixtures/demo material; do not infer app behavior from it.

# Dev Scripts (scripts/)

- `scripts/nrepl-eval.mjs` — Evaluate Clojure/ClojureScript code via nREPL.
  Supports `--backend` (port 6064) and `--frontend` (port 3447) aliases.
  See `mem:scripts/nrepl-eval`.
- `scripts/paren-repair` — Fix mismatched delimiters in Clojure/CLJS files
  and reformat with cljfmt. Run before lint checks when LLM edits break parens.
  See `mem:scripts/paren-repair`.
- `scripts/psql` — PostgreSQL client wrapper with devenv defaults.
  Companion: `scripts/db-schema` for DDL dumps. See `mem:scripts/psql`.
- `scripts/taiga.py` — Fetch public issues, user stories, and tasks from the
  Penpot Taiga project without authentication. See `mem:scripts/taiga`.
- `scripts/gh.py` — GitHub operations helper: list milestone issues, fetch PR
  details, compare against CHANGES.md. Requires `gh` CLI. See `mem:scripts/gh`.
- `scripts/error-reports.mjs` — Query error reports via RPC API with token
  authentication. Supports list/get operations with filtering and pagination.
  See `mem:scripts/error-reports`.

# Dependency graph

`frontend -> common`, `backend -> common`, `exporter -> common`, and `frontend -> render-wasm`. Changes in `common` can
affect frontend, backend, exporter, file migrations, and design-library behavior; validate across consumers when
semantics change.

# Working with Penpot designs

- Before automating or inspecting Penpot designs through the Plugin API, call the Penpot MCP `high_level_overview` tool.
- connection between the JavaScript plugin API and the ClojureScript code: `mem:frontend/plugin-api-to-cljs-binding`.
- executing ClojureScript code in the frontend: `mem:frontend/cljs-repl`.
- handling Clojure compiler errors, runtime patching and debug helpers: `mem:frontend/handling-errors-and-debugging`.

## Detecting Crashes

The Penpot frontend can crash silently from the JS API's perspective: `execute_code` calls return successfully, but 1-2s later the workspace becomes unusable (Internal Error page).
The `execute_code` tool then stops working, but `cljs_repl` still works. Use it to detect a crash via `(some? (:exception @app.main.store/state))`.
For details on handling crashes, read memory `mem:frontend/handling-crashes`.
