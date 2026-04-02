# Agent guide for `skia-rs-wasm`

TypeScript package: Skia WASM renderer wrapper, React canvas UI, web worker spatial index, and shared `@skia-rs-wasm/common/*` utilities.

## Commands

```bash
# From repo root (preferred)
pnpm --filter skia-rs-wasm run dev          # Vite dev server on :5173
pnpm --filter skia-rs-wasm run build        # Build lib + worker (two Vite configs)
pnpm --filter skia-rs-wasm run build:lib    # Library only → dist/renderer.es.js, dist/renderer.cjs
pnpm --filter skia-rs-wasm run build:worker # Worker only → dist/worker.js
pnpm --filter skia-rs-wasm run test         # Vitest (node environment)
pnpm --filter skia-rs-wasm run test:watch   # Vitest watch mode
pnpm --filter skia-rs-wasm run lint         # ESLint
```

## Build system

- **Library build** (`vite.config.ts`): React + TypeScript + Tailwind + SWC. Outputs ES module and CJS, externalises React, ReactDOM, and Zustand.
- **Worker build** (`vite.worker.config.ts`): Entry `src/lib/worker/worker-entry.ts` → single self-contained `dist/worker.js` (all deps inlined, no externals).
- **TypeScript**: `tsconfig.lib.json` (strict, ES2022, ESNext modules). Path alias `@/*` → `src/*`, `@skia-rs-wasm/common/*` → `src/lib/common/*`.
- **WASM dev serving**: custom Vite middleware sets correct `Content-Type: application/wasm` for `.wasm` files under `public/wasm/`.
- **Tests** (`vitest.config.ts`): `environment: 'node'`, test files in `test/lib/`.

## Initialization sequence

```
1. initWasmModule(wasmPath)   ← fetches public/wasm/render-wasm.js + .wasm, stored in workspace-store
2. initWorker(workerScriptUrl) ← spawns dist/worker.js, sets up postMessage bridge
3. (CanvasWrapper mounts)
4. initRendererClient(canvas) ← Renderer.builder().canvas(el).module(wasm).build()
                               └─ documentModel.loadDocument() → syncs initial page to worker + renderer
5. CanvasActorProvider wraps canvas with XState machine
```

## Public API boundary

`src/index.ts` is the **only** curated export surface. Consumers import from the `skia-rs-wasm` package root. Do not add barrel `index.ts` files elsewhere — import from the defining file (e.g. `@skia-rs-wasm/common/conversions`, not `@skia-rs-wasm/common`).

## Module layout

| Path | Role |
|------|------|
| `src/index.ts` | Public API — only curated re-exports |
| `src/lib/renderer/renderer.ts` | `Renderer` class (builder pattern, delegates to api/ bridges) |
| `src/lib/renderer/api/` | ~20 WASM bridge modules (canvas, fills, strokes, text, viewport, …) |
| `src/lib/renderer/machine/canvas-machine.ts` | XState v5 FSM for canvas interactions |
| `src/lib/renderer/signals/` | Preact signals (pointer, viewport, modifiers, drag previews) |
| `src/lib/renderer/store/` | Zustand + Valtio stores (workspace, doc, history, commits) |
| `src/lib/renderer/handlers/` | RxJS observable drag handlers (move, resize, rotate, gradient, …) |
| `src/lib/renderer/hooks/` | React hooks (viewport interactions, shortcuts, streams lifecycle) |
| `src/lib/renderer/components/` | React UI (Overlay, LayersPanel, RightSidePanel, FillEditor, …) |
| `src/lib/worker/` | Web Worker: quadtree spatial index, change processing |
| `src/lib/changes/` | ChangesBuilder for undo/redo |
| `src/lib/history/` | Undo/redo Zustand store |
| `src/lib/common/` | Shared helpers (conversions, uuid, types) — NO barrel index |
| `src/components/ui/` | shadcn/ui primitives (lowercase, matches components.json) |
| `public/wasm/` | Compiled `render-wasm.js` + `.wasm` for the dev server |

## State management (three layers)

### 1. Preact Signals (`src/lib/renderer/signals/`)
High-frequency, per-frame state. Written by RxJS handlers at pointer rate; read in React via `useSignalCoalesced()` to batch updates.

- `pointer.ts`: `pointerPos`, `modShift/Alt/Ctrl/Meta`, `viewport`, `movePreviewWorldDelta`, `rotatePreviewDeltaDeg`
- `selection.ts`: transient selection state during drag

### 2. Zustand + Valtio stores (`src/lib/renderer/store/`)
| File | Contents |
|------|----------|
| `workspace-store.ts` | `renderer`, `workerClient`, `wasmModule` references |
| `doc-proxy.ts` | Valtio `docProxy`: `meta`, `pageMap`, `selectedIds` (proxySet/proxyMap) |
| `document-model.ts` | `DocumentModel` — loads/saves pages, orchestrates sync |
| `commit.ts` | Commit pipeline: apply changes → docProxy → worker → renderer → history |
| `history-store.ts` | Zustand undo/redo frame stack |
| `shortcuts-store.ts` | Viewport shortcut bindings |

### 3. XState v5 machine (`src/lib/renderer/machine/canvas-machine.ts`)
Canvas interaction FSM. States: `idle` → `moving` / `resizing` / `rotating` / `selecting` / `draggingGradient` / `drawing` / `panning`. Each state uses a `fromObservable` actor (RxJS handler). Access via `CanvasActorProvider` / `useCanvasActor()`.

## WASM bridge

- **Module singleton** (`wasm-module.ts`): `ensureWasmModule()` fetches `render-wasm.js` once, caches the instance.
- **API modules** (`src/lib/renderer/api/`): typed wrapper functions per concern. Each takes the WASM module as a dependency, maps Penpot node properties to WASM calls.
- **Type definitions** (`wasm-types.d.ts`): FFI type declarations for the WASM module interface.

## Web Worker

Runs off-main-thread. No RxJS, no React. Receives `WorkerMessage` objects via `postMessage`, maintains a quadtree spatial index (`quadtree.ts`), processes add/delete/move/modify-object change messages (`process-changes.ts`). Communication via `WorkerClient` (`src/lib/worker-client.ts`).

## Commit pipeline

```
applyChanges(changes, inverseForUndo?)
  ├─ apply to docProxy (Valtio)
  ├─ send WorkerMessage to worker (index update)
  ├─ send WASM calls to renderer (visual update)
  └─ push undo frame to history-store (if inverse provided)
```

## Component folder naming

- `src/lib/components/` — **PascalCase** directories (`LayersPanel/`, `Overlay/`, `RightSidePanel/`, …).
- `src/components/ui/` — **lowercase** (shadcn standard, matches `components.json`).

## Key conventions

- No barrel `index.ts` files (except `src/index.ts`).
- Signals for high-frequency render-phase state; Zustand/Valtio for document/workspace state.
- RxJS observables only in `handlers/` — do not introduce RxJS into the worker.
- All drag interaction logic starts from XState events, executed as `fromObservable` actors.
