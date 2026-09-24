# Architecture Diagrams

Canonical, versioned diagrams of the Penpot runtime and its flows, meant for humans and agents.

## Format and location

- Source of truth: `.serena/diagrams/**/*.mmd`, one Mermaid diagram per file; plain text, git-versioned, diff-friendly, and readable by an LLM without rendering.
- Mermaid is the chosen format because LLMs generate it reliably and it renders in a browser and in HTML without a build step.
- `architecture-flow.mmd` is the runtime topology: browser client, JVM backend, stateful services, task-worker model, and export pipeline.
- `file-edit-flow.mmd` is the collaborative edit sequence: RPC mutation → PostgreSQL → Redis pub/sub → WebSocket → peer clients.
- The rendered HTML index is generated, not versioned: `.serena/diagrams/out/` is gitignored.

## Rendering

- HTML index: `scripts/render-diagrams` writes `.serena/diagrams/out/index.html`; Mermaid is imported from a pinned jsDelivr CDN, so opening the page needs network.
- SVG or PNG: `scripts/render-diagrams --format svg` (or `png`) calls `mmdc` from `@mermaid-js/mermaid-cli`; install it with `pnpm add -D @mermaid-js/mermaid-cli`.
- Render a single diagram with `scripts/render-diagrams --diagram architecture-flow`.
- Tests for the renderer: `python3 scripts/test_render_diagrams.py`.

## Content sources

- Module graph and cross-module dependencies: `mem:critical-info` (Dependency graph).
- Runtime services and the task-queue versus Pub/Sub topology that constrains horizontal scaling: `mem:prod-infra/core`.
- Backend internals, RPC, notifications, and storage backends: `mem:backend/core` and `mem:backend/storage`.
- The prose architecture pages, in C4 with PlantUML, live in `docs/technical-guide/developer/architecture/`; keep the Mermaid flow views consistent with them instead of duplicating their content.

## Maintenance invariants

- Update the affected `.mmd` in the same change that alters the topology it shows: a new module, a new service, or a changed flow.
- One concern per file; name files by topic with a `.mmd` suffix; keep node labels quoted so punctuation does not break the parser.
- Do not put diagram source inline in memories; reference the file so the graph stays small and the diagram stays renderable.
