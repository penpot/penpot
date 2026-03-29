/**
 * Zustand store for workspace/editor state: previews, viewport, renderer handles.
 * Canvas interaction modes (move/resize/…) live in `canvasMachine` (XState).
 */

import { create } from 'zustand'
import type { WasmModule } from '../wasm-types'
import type { ViewportData } from '../viewport'
import { Renderer } from '../index'
import type { Selrect } from 'penpot-exporter/types'
import type { SelectionRectResult } from '../types'
import type { WorkerClient } from '../../worker/types'
import { docProxy } from './doc-proxy'
import { type Rect } from '../selection-bounds'
import { viewportSignal } from '../signals/pointer'

export interface WorkspaceState {
  /** Union of selected nodes' selrects; set when selection changes. */
  selectionBounds: Rect | null
  selectionRect: Selrect | null
  /** Selection rect from WASM (getSelectionRect); overlay reads only this. Updated when modifiers or selection change. */
  wasmSelectionRect: SelectionRectResult | null
  /** Delta (deg) applied during active rotate drag; properties panel adds this to committed rotation for live display. */
  rotatePreviewDeltaDeg: number
  /** World-space translation during move drag (same as WASM modifier); drives live X/Y in the properties panel. */
  movePreviewWorldDelta: { x: number; y: number }
  /** Rubber-band preview in screen space (same convention as area marquee). */
  shapeDrawPreview: Selrect | null
  viewport: ViewportData | null
  /** Viewport used for hit-test; set one frame after apply so it matches the displayed frame. */
  lastAppliedViewport: ViewportData | null
  renderer: Renderer | null
  workerClient: WorkerClient | null

  // WASM Module state
  wasmModule: WasmModule | null
  isWasmModuleLoading: boolean
  wasmModuleError: Error | null

  // Actions
  setSelectionRect: (rect: Selrect | null) => void
  setWasmSelectionRect: (value: SelectionRectResult | null) => void
  refreshWasmSelectionRect: () => void
  setRotatePreviewDeltaDeg: (deg: number) => void
  setMovePreviewWorldDelta: (d: { x: number; y: number }) => void
  setShapeDrawPreview: (rect: Selrect | null) => void
  updateViewport: (data: ViewportData) => void
  setLastAppliedViewport: (data: ViewportData | null) => void
  setRenderer: (renderer: Renderer) => void
  setWorkerClient: (client: WorkerClient | null) => void

  // WASM Module actions
  setWasmModule: (module: WasmModule | null) => void
  setIsWasmModuleLoading: (loading: boolean) => void
  setWasmModuleError: (error: Error | null) => void
}

function isFiniteSelectionRect(value: SelectionRectResult | null): value is SelectionRectResult {
  if (!value) return false
  return (
    Number.isFinite(value.width) &&
    Number.isFinite(value.height) &&
    Number.isFinite(value.center.x) &&
    Number.isFinite(value.center.y) &&
    Number.isFinite(value.transform.a) &&
    Number.isFinite(value.transform.b) &&
    Number.isFinite(value.transform.c) &&
    Number.isFinite(value.transform.d) &&
    Number.isFinite(value.transform.e) &&
    Number.isFinite(value.transform.f)
  )
}

export const useWorkspaceStore = create<WorkspaceState>()((set, get) => ({
  selectionBounds: null,
  selectionRect: null,
  wasmSelectionRect: null,
  rotatePreviewDeltaDeg: 0,
  movePreviewWorldDelta: { x: 0, y: 0 },
  shapeDrawPreview: null,
  viewport: null,
  lastAppliedViewport: null,
  renderer: null,
  workerClient: null,
  wasmModule: null,
  isWasmModuleLoading: false,
  wasmModuleError: null,

  setSelectionRect: (rect) => set({ selectionRect: rect }),
  setWasmSelectionRect: (value) => set({ wasmSelectionRect: value }),
  refreshWasmSelectionRect: () => {
    const { renderer } = get()
    const selectedIds = docProxy.selectedIds
    if (!renderer || selectedIds.size === 0) {
      set({ wasmSelectionRect: null })
      return
    }
    const result = renderer.getSelectionRect(Array.from(selectedIds))
    set({ wasmSelectionRect: isFiniteSelectionRect(result) ? result : null })
  },
  setRotatePreviewDeltaDeg: (deg) => set({ rotatePreviewDeltaDeg: deg }),
  setMovePreviewWorldDelta: (d) => set({ movePreviewWorldDelta: d }),
  setShapeDrawPreview: (rect) => set({ shapeDrawPreview: rect }),
  updateViewport: (data) => {
    viewportSignal.value = data
    set({ viewport: data, lastAppliedViewport: data })
  },
  setLastAppliedViewport: (data) => set({ lastAppliedViewport: data }),
  setRenderer: (renderer) => set({ renderer, wasmSelectionRect: null }),
  setWorkerClient: (client) => set({ workerClient: client }),
  setWasmModule: (module) => set({ wasmModule: module }),
  setIsWasmModuleLoading: (loading) => set({ isWasmModuleLoading: loading }),
  setWasmModuleError: (error) => set({ wasmModuleError: error }),
}))
