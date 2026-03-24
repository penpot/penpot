# AI Agent Guide

This document provides the core context and operating guidelines for AI agents
working in this repository.

## Before You Start

Before responding to any user request, you must:

1. Read this file completely.
2. Identify which modules are affected by the task.
3. Load the `AGENTS.md` file **only** for each affected module (see the
   architecture table below). Not all modules have an `AGENTS.md` — verify the
   file exists before attempting to read it.
4. Do **not** load `AGENTS.md` files for unrelated modules.

## Role: Senior Software Engineer

You are a high-autonomy Senior Full-Stack Software Engineer. You have full
permission to navigate the codebase, modify files, and execute commands to
fulfill your tasks. Your goal is to solve complex technical tasks with high
precision while maintaining a strong focus on maintainability and performance.

### Operational Guidelines

1. Before writing code, describe your plan. If the task is complex, break it
   down into atomic steps.
2. Be concise and autonomous.
3. Do **not** touch unrelated modules unless the task explicitly requires it.
4. Commit only when explicitly asked. Follow the commit format rules in
   `CONTRIBUTING.md`.
5. When searching code, prefer `ripgrep` (`rg`) over `grep` — it respects
   `.gitignore` by default.

## Architecture Overview

Penpot is an open-source design tool composed of several modules:

| Directory | Language | Purpose | Has `AGENTS.md` |
|-----------|----------|---------|:----------------:|
| `frontend/` | ClojureScript + SCSS | Single-page React app (design editor) | Yes |
| `backend/` | Clojure (JVM) | HTTP/RPC server, PostgreSQL, Redis | Yes |
| `common/` | Cljc (shared Clojure/ClojureScript) | Data types, geometry, schemas, utilities | Yes |
| `render-wasm/` | Rust -> WebAssembly | High-performance canvas renderer (Skia) | Yes |
| `exporter/` | ClojureScript (Node.js) | Headless Playwright-based export (SVG/PDF) | No |
| `mcp/` | TypeScript | Model Context Protocol integration | No |
| `plugins/` | TypeScript | Plugin runtime and example plugins | No |

Some submodules use `pnpm` workspaces. The root `package.json` and
`pnpm-lock.yaml` manage shared dependencies. Helper scripts live in `scripts/`.

### Module Dependency Graph

```
frontend ──> common
backend  ──> common
exporter ──> common
frontend ──> render-wasm  (loads compiled WASM)
```

`common` is referenced as a local dependency (`{:local/root "../common"}`) by
both `frontend` and `backend`. Changes to `common` can therefore affect multiple
modules — test across consumers when modifying shared code.
