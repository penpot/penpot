# State Architecture: Zustand + Valtio + Signals + XState

This document describes the intended state architecture for the
`skia-rs-wasm` workspace layer and where each piece of functionality
lands today vs. where it is headed.

---

## Core principle

Four complementary tools handle distinct concerns:

| Tool | Role |
|------|------|
| **Valtio** | Reactive *document data* — the source-of-truth proxy that React components subscribe to for shape geometry, fills, page metadata, and selection IDs. |
| **Zustand** | Transient *workspace data* — renderer/worker handles, WASM loading. Interaction *modes* (moving vs resizing vs idle) live in XState, not here. |
| **Signals** (TC39 / `@preact/signals`) | High-frequency *per-frame values* — pointer position, modifier keys, viewport pan/zoom (`viewport`), move/rotate preview deltas, overlay rects (`wasmSelectionRect`, area marquee `selectionRect`, draw rubber-band `shapeDrawPreview`). React reads hot values via `useSignalCoalesced` (RAF-batched). |
| **XState** | Canvas *interaction modes* — `canvasMachine` (v5) with states `idle` \| `moving` \| `rotating` \| `resizing` \| `selecting` \| `drawingShape` \| `panning`; RxJS handlers run as `fromObservable` invoked actors; mounted from `CanvasWrapper`. |

---

## 1. Document layer — Valtio ✅ Done

```
docProxy (valtio/proxy)
  ├── meta: DocumentMeta | null
  ├── pageMap: Map<string, IndexedPage>          ← proxyMap
  ├── currentPageId: string | null
  └── selectedIds: Set<string>                   ← proxySet
```

**Status: complete.**

- `docProxy` is the single mutable document object.
- React components call `useSnapshot(docProxy)` to subscribe.
- `selectedIds` lives here as a `proxySet`; `document-selection.ts`
  syncs the WASM selection overlay signal (`querySelectionRect` → `wasmSelectionRect`) whenever selection changes.
- Mutations go through the commit pipeline (`commitChanges` →
  `applyChangesLocally` → `processChanges` → `docProxy.pageMap.set`).

**What Valtio gives us:** mutation-aware deep reactivity for free,
without manual action creators. React tree only re-renders the subtrees
that depend on changed properties.

---

## 2. Workspace store — Zustand ✅ Done (runtime handles; modes in XState)

```
useWorkspaceStore (zustand)
  └── Runtime handles
      ├── renderer: Renderer | null
      ├── workerClient: WorkerClient | null
      ├── wasmModule: WasmModule | null
      └── isWasmModuleLoading / wasmModuleError
```

**Status: complete.** Interaction booleans (`isMoving`, `drawTool`, `resizeHandle`, …) were removed; they are represented by **`canvasMachine`** state and context (see §4).

`setRenderer` queries WASM (`querySelectionRect`) and writes `wasmSelectionRect` when a renderer is set and there is a non-empty selection, so the overlay stays in sync without a React hook.

```
useHistoryStore (zustand)
  ├── undoStack: CommitFrame[]   (max 200)
  └── redoStack: CommitFrame[]
```

**Status: complete.**

```
useViewportShortcutsStore (zustand)
  └── viewportShortcuts: ShortcutsConfig
```

**Status: complete.** Live modifier keys (`shift`, `alt`, `ctrl`, `meta`) live in
[`signals/pointer.ts`](../src/lib/renderer/signals/pointer.ts), not Zustand;
`getModifierKeys()` in [`shortcuts-store.ts`](../src/lib/renderer/store/shortcuts-store.ts)
reads those signals.

---

## 3. Per-frame pointer/modifier signals — Done

**Implementation:** [`src/lib/renderer/signals/pointer.ts`](../src/lib/renderer/signals/pointer.ts)

- **`pointerPos`**, **`modShift`**, **`modAlt`**, **`modCtrl`**, **`modMeta`**, **`keyboardSpace`**
  — updated from `use-streams`, `canvas-wrapper` (keyboard modifiers), and
  canvas/overlay pointerdown sites.
- **`viewport`** — canonical pan/zoom (`ViewportData | null`); writers include
  [`canvas-wrapper.tsx`](../src/lib/renderer/canvas-wrapper.tsx) (`onViewportUpdate`),
  [`viewport-actions.ts`](../src/lib/renderer/hooks/viewport-actions.ts), and
  [`document-model.ts`](../src/lib/renderer/store/document-model.ts) on page init.
- **`movePreviewWorldDelta`**, **`rotatePreviewDeltaDeg`** — written by `move.ts` /
  `rotate.ts` on every pointer event during drag; reset on release and in
  `clearSelection`. React UI (e.g. layout fields) subscribes via
  [`useSignalCoalesced`](../src/lib/renderer/signals/use-signal-coalesced.ts)
  so updates are RAF-batched.
- **`worldPointerPos`** — `computed()` from `pointerPos` + `viewport`.
- **`signalToObservable`** — bridges a signal into RxJS so drag handlers still return
  `Observable<void>` for XState `fromObservable` without keeping `BehaviorSubject`
  sources.

```ts
// signals/pointer.ts — summary
import { signal, computed } from '@preact/signals-core'

export const pointerPos = signal<Point | null>(null)
export const modShift = signal(false)
export const modAlt = signal(false)
export const modCtrl = signal(false)
export const modMeta = signal(false)
export const keyboardSpace = signal(false)
export const viewport = signal<ViewportData | null>(null)
export const movePreviewWorldDelta = signal<Point>({ x: 0, y: 0 })
export const rotatePreviewDeltaDeg = signal(0)

export const worldPointerPos = computed(() => { /* screenToWorld */ })
```

**RxJS** remains for operator pipelines (`scan`, `takeUntil`, …) and `dragStopper`;
only the previous `BehaviorSubject` holders were replaced.

### Overlay rect signals — Done

**Implementation:** [`src/lib/renderer/signals/selection.ts`](../src/lib/renderer/signals/selection.ts)

- **`wasmSelectionRect`** — selection bounds from WASM (`Renderer.getSelectionRect`). Updated on selection change (`document-selection`), `setRenderer`, move/rotate preview during drag, and `querySelectionRect` after resize/move/rotate commits.
- **`selectionRect`** — area marquee in screen space (`selection.ts`).
- **`shapeDrawPreview`** — draw-rect rubber band (`draw-shape.ts`).
- **`querySelectionRect(renderer, ids)`** — stateless helper; call sites assign `wasmSelectionRect.value` with the result (no Zustand `refresh` action).

`SelectionOverlay` and dev `SelectionInfo` subscribe via `useSignalCoalesced` (one React commit per animation frame).

---

## 4. Interaction state machine — XState ✅ Done (canvas)

**Implementation:** [`src/lib/renderer/machine/canvas-machine.ts`](../src/lib/renderer/machine/canvas-machine.ts)

- Single **`canvasMachine`** (XState v5) replaces scattered Zustand flags
  (`isMoving`, `isResizing`, `isRotating`, `isSelecting`, `isDrawingShape`,
  `drawTool`, `resizeHandle`, `rotationCorner`, area-select mode, …).
- **Invoked actors** use `fromObservable` over the existing handlers (still
  **RxJS** inside): `move.ts`, `rotate.ts`, `resize.ts`, `selection.ts`,
  `draw-shape.ts`. Pointer-up / completion is driven by each handler’s
  `dragStopper()` pipeline; the machine returns to `idle` on actor **`onDone`**
  / **`onError`**.
- **Context** holds `drawTool`, `resizeHandle`, `rotationCorner`,
  `areaSelectionAppend`, `areaSelectionRemove` when relevant.
- **Library entry:** [`CanvasWrapper`](../src/lib/renderer/canvas-wrapper.tsx) calls
  `useActorRef(canvasMachine)` and wraps the canvas column in
  **`CanvasActorProvider`**. UI that must call **`useCanvasActor`** or
  **`useSelector(actor, …)`** but sits *outside* the canvas DOM subtree should
  be passed as **`overlays?: ReactNode`** on `CanvasWrapper` (same React
  subtree as the provider). Advanced embeds can still use **`CanvasActorProvider`**
  manually (exported from the package).

**State chart (summary):**

```
canvasMachine
  idle
    POINTER_DOWN_ON_SELECTION → moving     (invoke moveActor)
    POINTER_DOWN_ON_CORNER    → resizing   (invoke resizeActor; context.resizeHandle)
    POINTER_DOWN_ON_ROTATION  → rotating   (invoke rotateActor; context.rotationCorner)
    POINTER_DOWN_ON_CANVAS    → selecting  (invoke selectActor; append/remove in context)
    POINTER_DOWN_DRAW         → drawingShape (invoke drawActor)
    PAN_START                 → panning
    DRAW_TOOL_ACTIVATE / DRAW_TOOL_DEACTIVATE → context.drawTool (root-level)

  moving | rotating | resizing | selecting | drawingShape
    → idle on invoked actor onDone / onError (clear handle/corner where needed)

  panning
    PAN_END → idle
```

Viewport pan deltas still run in **`use-viewport-interactions.ts`**; only
**mode** `panning` is represented on the machine (`PAN_START` / `PAN_END`).

**Benefits (as intended):**
- One place for transitions; impossible combos like `moving` ∧ `rotating` are ruled out.
- Devtools-friendly; actors are testable in isolation.
- Handlers stay thin RxJS pipelines; XState owns lifecycle and mode.

---

## 5. Commit pipeline ✅ Done

```
applyModifiersAndCommit(entries)
  └── propagateModifiers(module, entries)      ← WASM: apply transform
  └── applyTransformToNode(node, matrix)       ← geometry update
  └── appendModObjPair(builder, ...)           ← build Change objects
  └── commitChanges({ redoChanges, undoChanges })
      ├── applyChangesLocally(pageId, changes)
      │   ├── processChanges(page, changes)    ← clojurescript-ported
      │   └── docProxy.pageMap.set(pageId, updated)   ← Valtio mutation
      ├── updateWorkerIndexes(workerClient, ...) ← background, non-blocking
      └── useHistoryStore.pushCommitFrame(...)  ← undo/redo
```

**Live preview (status: done):**

During drag, the properties panel shows geometry derived from the same
matrices that will be committed. **`NodePropertyPanel`** uses XState
`matches('moving')` / `matches('rotating')` (via `useSelector` on the canvas
actor) together with signal preview deltas (via `useSignalCoalesced`):

```
moving    → applyTransformToNode(node, translateMatrix(dx, dy))   ← movePreviewWorldDelta (signal)
rotating  → applyTransformToNode(node, rotationMatrixAroundPoint(…)) ← rotatePreviewDeltaDeg (signal)
```

Values are computed in a `useMemo` when coalesced preview values or machine-derived
flags change so X, Y, W, H, and Rotation track the cursor without a commit
round-trip.

---

## 6. Rendering pipeline ✅ Done (WASM side)

```
WASM canvas  ←  setMoveModifiersNoRender  ←  handler (every pointer event)
             ←  requestRenderFrame         ←  handler (~60 Hz gate)
             ←  flushRenderSync            ←  on commit
             ←  cleanModifiers             ←  after commit / on cancel
```

Move/rotate update the overlay signal on every pointer event from a baseline rect
(`translateSelectionRectWorld` / `rotateSelectionRectAroundPivot`); React reads via
`useSignalCoalesced` (RAF-batched). WASM renders up to ~60 fps. Resize uses
`querySelectionRect` after each throttled `setMoveModifiersAndRender`.

---

## 7. Off-main-thread spatial index — Web Worker ✅ Done

```
WorkerClient  →  postMessage({ type: 'update-page', ... })
              →  postMessage({ type: 'query-at-point', ... })
              ←  { matches: string[] }
```

The worker maintains a quadtree for hit-testing. Committed document
changes are forwarded after commit (non-blocking); query-at-point
results are used by the viewport interaction layer to pick shapes.

---

## Summary of completion status

| Feature | Today | Next |
|---------|-------|------|
| Document proxy (Valtio) | ✅ `docProxy` with `proxyMap`/`proxySet` | — |
| Selected IDs in Valtio | ✅ `docProxy.selectedIds` | — |
| Workspace store (Zustand) | ✅ Renderer/worker/WASM | — |
| Canvas interaction modes | ✅ `canvasMachine` + `CanvasWrapper` / `overlays` | — |
| History (Zustand) | ✅ `useHistoryStore` | — |
| Shortcuts (Zustand) | ✅ `useViewportShortcutsStore` (config only) | — |
| Modifier keys | ✅ Signals in `signals/pointer.ts` | — |
| Pointer position / per-frame input | ✅ Signals + `signalToObservable` → RxJS handlers | — |
| Live drag preview (overlay) | ✅ Signals + `useSignalCoalesced` | — |
| Live drag preview (panel) | ✅ `useMemo` + XState mode + signal deltas | — |
| Move / rotate / resize / select / draw handlers | ✅ RxJS + `fromObservable` actors | — |
| Panning | ✅ Deltas in `useViewportInteractions`; mode `panning` on machine | Optional: unify more in machine |
| Commit pipeline | ✅ Full undo/redo via `Change[]` | — |
| WASM modifier throttle | ✅ 60 Hz gate + overlay signals | — |
| Web Worker spatial index | ✅ Quadtree, incremental updates | — |
| XState canvas machine | ✅ `canvas-machine.ts`, context in `canvas-actor-context.tsx` | — |
| Signals for pointer/modifiers/viewport | ✅ `signals/pointer.ts` | — |
| Signals for overlay rects | ✅ `signals/selection.ts` + `querySelectionRect` | — |
