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
| **Zustand** | Transient *workspace interaction state* — flags and scalars (isMoving, viewport, wasmSelectionRect, drag preview deltas) that must survive across renders but do not belong in the document. |
| **Signals** (TC39 / `@preact/signals`) | High-frequency *per-frame derived values* — current-frame pointer position, zoom level, modifier-key booleans — that must update at pointer rate without causing React re-renders. |
| **XState** | Complex *interaction state machines* — move, rotate, resize, area-select, draw-shape — each modeled as an explicit state machine with guards, actions, and effects rather than scattered `isMoving/isResizing/isRotating` booleans. |

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
  syncs derived state (bounds, WASM selection rect) whenever it changes.
- Mutations go through the commit pipeline (`commitChanges` →
  `applyChangesLocally` → `processChanges` → `docProxy.pageMap.set`).

**What Valtio gives us:** mutation-aware deep reactivity for free,
without manual action creators. React tree only re-renders the subtrees
that depend on changed properties.

---

## 2. Workspace interaction state — Zustand ✅ Done

```
useWorkspaceStore (zustand)
  ├── Drag preview
  │   ├── wasmSelectionRect: SelectionRectResult | null
  │   ├── movePreviewWorldDelta: { x, y }         ← drives live X/Y in panel
  │   ├── rotatePreviewDeltaDeg: number            ← drives live Rotation in panel
  │   └── selectionBounds: Rect | null
  ├── Interaction flags (interim — will move to XState)
  │   ├── isMoving / isResizing / isRotating / isSelecting
  │   ├── resizeHandle / rotationCorner
  │   ├── isPanning / isDrawingShape
  │   └── drawTool / shapeDrawPreview
  ├── Viewport
  │   ├── viewport: ViewportData | null
  │   └── lastAppliedViewport: ViewportData | null
  └── Runtime handles
      ├── renderer: Renderer | null
      ├── workerClient: WorkerClient | null
      ├── wasmModule: WasmModule | null
      └── isWasmModuleLoading / wasmModuleError
```

**Status: complete (core); partially refactorable toward XState.**

Drag preview fields (`movePreviewWorldDelta`, `rotatePreviewDeltaDeg`,
`wasmSelectionRect`) are updated synchronously on every pointer event
so the SVG overlay and property panel always reflect the latest pointer
position without waiting for a WASM frame.

```
useHistoryStore (zustand)
  ├── undoStack: CommitFrame[]   (max 200)
  └── redoStack: CommitFrame[]
```

**Status: complete.**

```
useViewportShortcutsStore (zustand)
  ├── viewportShortcuts: ShortcutsConfig
  └── modifierKeys: { shift, alt, ctrl, meta }
```

**Status: complete.**

---

## 3. Per-frame pointer/modifier signals — TODO

The current approach uses **RxJS BehaviorSubjects** as a poor-man's
signals:

```ts
// src/lib/renderer/streams/index.ts — today
export const mousePosition$          = new BehaviorSubject<Point | null>(null)
export const mousePositionShift$     = new BehaviorSubject<boolean>(false)
export const mousePositionAlt$       = new BehaviorSubject<boolean>(false)
export const mousePositionMod$       = new BehaviorSubject<boolean>(false)
export const keyboardSpace$          = new BehaviorSubject<boolean>(false)
```

These are piped through RxJS operators inside every drag handler
(`move.ts`, `rotate.ts`, `resize.ts`), which works but leaks
subscription management into handler code.

**Target with Signals:**

```ts
// signals/pointer.ts (planned)
import { signal, computed } from '@preact/signals-core'

export const pointerPos      = signal<{ x: number; y: number } | null>(null)
export const modShift        = signal(false)
export const modAlt          = signal(false)
export const modCtrl         = signal(false)
export const keyboardSpace   = signal(false)

// Derived — zero-cost if nobody reads them
export const worldPointerPos = computed(() => {
  const pos = pointerPos.value
  const vp  = viewportSignal.value
  if (!pos || !vp) return null
  return screenToWorld(vp, pos.x, pos.y)
})
```

**Benefits:**
- Handlers read `.value` instead of subscribing; no `subscribe` /
  `unsubscribe` in every handler.
- `computed()` values are lazy and automatically cached — world
  position only recomputes when either `pointerPos` or `viewport`
  changes.
- React components that display pointer position can subscribe without
  triggering a full Zustand re-render cycle.

---

## 4. Interaction state machines — XState (partially done)

Each tool is an explicit state machine. Today the logic lives in
**RxJS pipelines** inside handler files; the hooks activate them by
toggling Zustand flags:

```
useRotate / useMove / useResize / useSelection / useDrawShape
  ↓ reads isRotating/isMoving/... from Zustand
  ↓ subscribes to BehaviorSubject streams
  ↓ handler emits Observable<void>
  ↓ on pointer-up: applyModifiersAndCommit
```

**What is done today (RxJS + Zustand flags):**

- ✅ `move.ts` — translate matrix + overlay preview + throttled render
- ✅ `rotate.ts` — rotation matrix + overlay preview + live angle
- ✅ `resize.ts` — scale matrix + overlay preview
- ✅ `draw-shape.ts` — rubber-band rect preview
- ✅ `selection.ts` — area marquee + single click pick

**Target with XState:**

```
canvasMachine (XState v5)
  states:
    idle
      on POINTER_DOWN_ON_SELECTION → moving
      on POINTER_DOWN_ON_CORNER    → resizing
      on POINTER_DOWN_ON_ROTATION  → rotating
      on POINTER_DOWN_ON_CANVAS    → selecting (area marquee)
      on DRAW_TOOL_ACTIVE + POINTER_DOWN → drawingShape

    moving (entry: captureBaseline)
      on POINTER_MOVE → [action: updateOverlay, action: setWasmModifiers]
      on POINTER_UP   → [action: commit] → idle

    rotating (entry: captureBaseline)
      on POINTER_MOVE → [action: updateOverlay, action: setWasmModifiers]
      on POINTER_UP   → [action: commit] → idle

    resizing (entry: captureBaseline)
      on POINTER_MOVE → [action: updateOverlay, action: setWasmModifiers]
      on POINTER_UP   → [action: commit] → idle

    selecting
      on POINTER_MOVE → [action: updateMarquee]
      on POINTER_UP   → [action: applyAreaSelection] → idle

    drawingShape
      on POINTER_MOVE → [action: updateRubberBand]
      on POINTER_UP   → [action: createShape] → idle

    panning
      on POINTER_MOVE → [action: pan viewport]
      on POINTER_UP   → idle
```

**Benefits of XState here:**
- One authoritative place to see every transition and guard.
- Impossible states are structurally impossible (can't be
  `isMoving && isRotating` simultaneously).
- Devtools visualization for free.
- Testable without a browser or canvas.

**Migration path:** The RxJS handlers are pure functions
(`Observable<void>`) — they can be wrapped as XState actors
(`fromObservable(startMoveSelected)`) with minimal changes. The Zustand
flags become XState context/state and can be removed one by one.

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
matrices that will be committed:

```
isMoving  → applyTransformToNode(node, translateMatrix(dx, dy))
isRotating → applyTransformToNode(node, rotationMatrixAroundPoint(cx, cy, Δdeg))
```

Values are computed in a `useMemo` on every store update so X, Y, W,
H, and Rotation track the cursor in real time without a commit round-trip.

---

## 6. Rendering pipeline ✅ Done (WASM side)

```
WASM canvas  ←  setMoveModifiersNoRender  ←  handler (every pointer event)
             ←  requestRenderFrame         ←  handler (~60 Hz gate)
             ←  flushRenderSync            ←  on commit
             ←  cleanModifiers             ←  after commit / on cancel
```

The SVG overlay is updated **synchronously** on every pointer event
from a pre-computed baseline rect (`translateSelectionRectWorld` /
`rotateSelectionRectAroundPivot`), keeping the overlay at full pointer
rate while WASM renders up to ~60 fps.

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
| Workspace flags (Zustand) | ✅ `useWorkspaceStore` | Fold into XState context |
| History (Zustand) | ✅ `useHistoryStore` | — |
| Shortcuts/modifiers (Zustand) | ✅ `useViewportShortcutsStore` | Move modifier state to Signals |
| Pointer streams | ⚠️ `BehaviorSubject` (works) | Migrate to Signals |
| Live drag preview (overlay) | ✅ Synchronous per-event | — |
| Live drag preview (panel) | ✅ `useMemo` via `applyTransformToNode` | — |
| Move handler | ✅ RxJS + WASM modifiers | Wrap as XState actor |
| Rotate handler | ✅ RxJS + WASM modifiers | Wrap as XState actor |
| Resize handler | ✅ RxJS + WASM modifiers | Wrap as XState actor |
| Draw-shape handler | ✅ RxJS rubber-band | Wrap as XState actor |
| Area-select handler | ✅ RxJS marquee | Wrap as XState actor |
| Panning | ✅ `useViewportInteractions` | Wrap as XState actor |
| Commit pipeline | ✅ Full undo/redo via `Change[]` | — |
| WASM modifier throttle | ✅ 60 Hz gate + sync overlay | — |
| Web Worker spatial index | ✅ Quadtree, incremental updates | — |
| XState canvas machine | ❌ Not started | Replace scattered boolean flags |
| Signals for pointer/modifiers | ❌ Not started | Replace BehaviorSubject |
