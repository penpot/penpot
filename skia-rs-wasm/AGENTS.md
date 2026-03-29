# IA Agent guide for `skia-rs-wasm`

TypeScript package: Skia WASM renderer wrapper, React canvas UI, worker index, and shared `@skia-rs-wasm/common/*` utilities.

## Module layout

- **Public API (only curated export surface):** [src/index.ts](src/index.ts). Consumers import from the `skia-rs-wasm` package root.
- **Renderer core:** [src/lib/renderer/renderer.ts](src/lib/renderer/renderer.ts) (`Renderer`, `RendererBuilder`).
- **Worker bundle entry:** [src/lib/worker/worker-entry.ts](src/lib/worker/worker-entry.ts) (built via [vite.worker.config.ts](vite.worker.config.ts)).
- **Shared helpers:** [src/lib/common/](src/lib/common/) — import concrete modules (e.g. `@skia-rs-wasm/common/conversions`), not a folder `index.ts`.

## Do not add barrel files

- Do **not** add `index.ts` files that only re-export from other modules. Import from the defining file (see repo TypeScript standards / no barrel files).
- Do **not** use `index.ts` as a dumping ground for large implementations; use named files (`renderer.ts`, `worker-entry.ts`, etc.).
- The **only** `index.ts` in this package should remain [src/index.ts](src/index.ts) as the library public API boundary.

## Component folder naming

Under [src/lib/components/](src/lib/components/), directories that group React feature UI (parents of `.tsx` components) use **PascalCase**, not kebab-case (e.g. `LayersPanel/`, `Overlay/` for selection SVG overlay).

**Exception:** keep [src/components/ui/](src/components/ui/) lowercase — it matches shadcn’s default path in [components.json](components.json) (`@/components/ui`). Other feature folders under `src/components/` should still use PascalCase.

## Commands

From repo root: `pnpm --filter skia-rs-wasm run test`, `pnpm --filter skia-rs-wasm run build`. The package `lint` script may include nested `packages/`; for source-only checks, scope ESLint to paths you changed.
