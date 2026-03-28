/**
 * Zustand store for workspace/editor interaction state.
 */

import { create } from 'zustand'
import type { WasmModule } from '../wasm-types'
import type { ViewportData } from '../viewport'
import { Renderer } from '../index'
import type { Selrect } from 'penpot-exporter/types'

/** Active tool for creating shapes on the canvas (toolbar). `null` = select / default. */
export type DrawTool = 'rect'
import type { ResizeHandlePosition, SelectionRectResult } from '../types'
import type { WorkerClient } from '../../worker/types'
import { docProxy } from './doc-proxy'
import { type Rect } from '../selection-bounds'

export interface WorkspaceState {
  // State
  /** Union of selected nodes' selrects; set when selection changes. */
  selectionBounds: Rect | null
  selectionRect: Selrect | null
  /** Selection rect from WASM (getSelectionRect); overlay reads only this. Updated when modifiers or selection change. */
  wasmSelectionRect: SelectionRectResult | null
  isSelecting: boolean
  isMoving: boolean
  isResizing: boolean
  resizeHandle: ResizeHandlePosition | null
  isRotating: boolean
  /** Corner that started the rotation drag; used to keep the same rotation cursor during drag. */
  rotationCorner: ResizeHandlePosition | null
  /** Delta (deg) applied during active rotate drag; properties panel adds this to committed rotation for live display. */
  rotatePreviewDeltaDeg: number
  /** World-space translation during move drag (same as WASM modifier); drives live X/Y in the properties panel. */
  movePreviewWorldDelta: { x: number; y: number }
  /** When starting area selection with modifier: append (shift) or remove (shift+mod). */
  areaSelectionAppend: boolean
  areaSelectionRemove: boolean
  /** True while the user is actively panning (e.g. middle-drag); used to defer Figma viewport sync until pan end. */
  isPanning: boolean
  /** When set, next drag on the canvas creates a shape (e.g. rectangle) instead of selecting. */
  drawTool: DrawTool | null
  /** True while a shape drag is in progress (rubber-band). */
  isDrawingShape: boolean
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
  setIsSelecting: (is: boolean) => void
  setIsMoving: (is: boolean) => void
  setIsResizing: (is: boolean) => void
  setResizeHandle: (handle: ResizeHandlePosition | null) => void
  setIsRotating: (is: boolean) => void
  setRotationCorner: (corner: ResizeHandlePosition | null) => void
  setRotatePreviewDeltaDeg: (deg: number) => void
  setMovePreviewWorldDelta: (d: { x: number; y: number }) => void
  setAreaSelectionMode: (append: boolean, remove: boolean) => void
  setIsPanning: (value: boolean) => void
  setDrawTool: (tool: DrawTool | null) => void
  setIsDrawingShape: (value: boolean) => void
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
  isSelecting: false,
  isMoving: false,
  isResizing: false,
  resizeHandle: null as ResizeHandlePosition | null,
  isRotating: false,
  rotationCorner: null as ResizeHandlePosition | null,
  rotatePreviewDeltaDeg: 0,
  movePreviewWorldDelta: { x: 0, y: 0 },
  areaSelectionAppend: false,
  areaSelectionRemove: false,
  isPanning: false,
  drawTool: null,
  isDrawingShape: false,
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
  setIsSelecting: (is) => set({ isSelecting: is }),
  setIsMoving: (is) =>
    set(
      is
        ? { isMoving: true }
        : { isMoving: false, movePreviewWorldDelta: { x: 0, y: 0 } },
    ),
  setIsResizing: (is) => set({ isResizing: is }),
  setResizeHandle: (handle) => set({ resizeHandle: handle }),
  setIsRotating: (is) => set({ isRotating: is }),
  setRotationCorner: (corner) => set({ rotationCorner: corner }),
  setRotatePreviewDeltaDeg: (deg) => set({ rotatePreviewDeltaDeg: deg }),
  setMovePreviewWorldDelta: (d) => set({ movePreviewWorldDelta: d }),
  setAreaSelectionMode: (append, remove) => set({ areaSelectionAppend: append, areaSelectionRemove: remove }),
  setIsPanning: (value) => set({ isPanning: value }),
  setDrawTool: (tool) => set({ drawTool: tool }),
  setIsDrawingShape: (value) => set({ isDrawingShape: value }),
  setShapeDrawPreview: (rect) => set({ shapeDrawPreview: rect }),
  updateViewport: (data) => set({ viewport: data, lastAppliedViewport: data }),
  setLastAppliedViewport: (data) => set({ lastAppliedViewport: data }),
  setRenderer: (renderer) => set({ renderer, wasmSelectionRect: null }),
  setWorkerClient: (client) => set({ workerClient: client }),
  setWasmModule: (module) => set({ wasmModule: module }),
  setIsWasmModuleLoading: (loading) => set({ isWasmModuleLoading: loading }),
  setWasmModuleError: (error) => set({ wasmModuleError: error }),
}))
