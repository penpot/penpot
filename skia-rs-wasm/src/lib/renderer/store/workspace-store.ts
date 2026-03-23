/**
 * Zustand store for workspace state management.
 * Holds app state: selectedNodes, pageId, selectedIds, selection, viewport, renderer, worker.
 * Document/page data lives in DocumentModel; it pushes selectedNodes here and (for dev) currentPageNodes to workspace-dev-store.
 */

import { create } from 'zustand'
import type { WasmModule } from '../wasm-types'
import type { ViewportData } from '../viewport'
import { Renderer } from '../index'
import type { Selrect, Change } from 'penpot-exporter/types'

/** Active tool for creating shapes on the canvas (toolbar). `null` = select / default. */
export type DrawTool = 'rect'
import type { ResizeHandlePosition, SelectionRectResult } from '../types'
import type { WorkerClient } from '../../worker/types'
import type { IndexedPage, IndexedNode } from '../../worker/types'
import { getSelectionBounds, type Rect } from '../selection-bounds'

/** Implemented by DocumentModel; used so the store and page-crud can call methods without importing the class. */
export interface IDocumentModel {
  getNode(id: string): IndexedNode | undefined
  getSelectedNodes(selectedIds: Set<string>): IndexedNode[]
  getPage(id: string): IndexedPage | undefined
  /** Current page id, or the only page id when the document has a single page (for commits when workspace `pageId` is unset). */
  getActiveOrSinglePageId(): string | null
  setActivePage(pageId: string): Promise<void>
  addPage(page: IndexedPage): Promise<void>
  deletePage(pageId: string): Promise<void>
  applyChanges(
    changes: Change[],
    options?: { pageId?: string; undoChanges?: Change[] }
  ): Promise<void>
}

export interface WorkspaceState {
  // State
  documentModel: IDocumentModel | null
  selectedNodes: IndexedNode[]
  pageId: string | null
  selectedIds: Set<string>
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
  setDocumentModel: (model: IDocumentModel | null) => void
  setSelectedNodes: (nodes: IndexedNode[]) => void
  setPageId: (id: string | null) => void
  setSelectedIds: (ids: Set<string>) => void
  setSelectionRect: (rect: Selrect | null) => void
  setWasmSelectionRect: (value: SelectionRectResult | null) => void
  refreshWasmSelectionRect: () => void
  setIsSelecting: (is: boolean) => void
  setIsMoving: (is: boolean) => void
  setIsResizing: (is: boolean) => void
  setResizeHandle: (handle: ResizeHandlePosition | null) => void
  setIsRotating: (is: boolean) => void
  setRotationCorner: (corner: ResizeHandlePosition | null) => void
  setAreaSelectionMode: (append: boolean, remove: boolean) => void
  setIsPanning: (value: boolean) => void
  setDrawTool: (tool: DrawTool | null) => void
  setIsDrawingShape: (value: boolean) => void
  setShapeDrawPreview: (rect: Selrect | null) => void
  updateViewport: (data: ViewportData) => void
  setLastAppliedViewport: (data: ViewportData | null) => void
  setRenderer: (renderer: Renderer) => void
  setWorkerClient: (client: WorkerClient | null) => void
  clearSelection: () => void

  // WASM Module actions
  setWasmModule: (module: WasmModule | null) => void
  setIsWasmModuleLoading: (loading: boolean) => void
  setWasmModuleError: (error: Error | null) => void
}

const EMPTY_NODES: IndexedNode[] = []

export const useWorkspaceStore = create<WorkspaceState>()((set, get) => ({
  documentModel: null,
  selectedNodes: EMPTY_NODES,
  pageId: null,
  selectedIds: new Set(),
  selectionBounds: null,
  selectionRect: null,
  wasmSelectionRect: null,
  isSelecting: false,
  isMoving: false,
  isResizing: false,
  resizeHandle: null as ResizeHandlePosition | null,
  isRotating: false,
  rotationCorner: null as ResizeHandlePosition | null,
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

  setDocumentModel: (model) => set({ documentModel: model }),
  setSelectedNodes: (nodes) => set({
    selectedNodes: nodes,
    selectionBounds: getSelectionBounds(nodes),
  }),
  setPageId: (id) => set({ pageId: id }),
  setSelectedIds: (ids) => {
    const { documentModel } = get()
    const selectedNodes = documentModel ? documentModel.getSelectedNodes(ids) : EMPTY_NODES
    set({
      selectedIds: ids,
      selectedNodes,
      selectionBounds: getSelectionBounds(selectedNodes),
    })
  },
  setSelectionRect: (rect) => set({ selectionRect: rect }),
  setWasmSelectionRect: (value) => set({ wasmSelectionRect: value }),
  refreshWasmSelectionRect: () => {
    const { renderer, selectedIds } = get()
    if (!renderer || selectedIds.size === 0) {
      set({ wasmSelectionRect: null })
      return
    }
    const result = renderer.getSelectionRect(Array.from(selectedIds))
    set({ wasmSelectionRect: result })
  },
  setIsSelecting: (is) => set({ isSelecting: is }),
  setIsMoving: (is) => set({ isMoving: is }),
  setIsResizing: (is) => set({ isResizing: is }),
  setResizeHandle: (handle) => set({ resizeHandle: handle }),
  setIsRotating: (is) => set({ isRotating: is }),
  setRotationCorner: (corner) => set({ rotationCorner: corner }),
  setAreaSelectionMode: (append, remove) => set({ areaSelectionAppend: append, areaSelectionRemove: remove }),
  setIsPanning: (value) => set({ isPanning: value }),
  setDrawTool: (tool) => set({ drawTool: tool }),
  setIsDrawingShape: (value) => set({ isDrawingShape: value }),
  setShapeDrawPreview: (rect) => set({ shapeDrawPreview: rect }),
  updateViewport: (data) => set({ viewport: data, lastAppliedViewport: data }),
  setLastAppliedViewport: (data) => set({ lastAppliedViewport: data }),
  setRenderer: (renderer) => set({ renderer, wasmSelectionRect: null }),
  setWorkerClient: (client) => set({ workerClient: client }),
  clearSelection: () => set({
    selectedIds: new Set(),
    selectionBounds: null,
    selectionRect: null,
    wasmSelectionRect: null,
    selectedNodes: EMPTY_NODES,
  }),
  setWasmModule: (module) => set({ wasmModule: module }),
  setIsWasmModuleLoading: (loading) => set({ isWasmModuleLoading: loading }),
  setWasmModuleError: (error) => set({ wasmModuleError: error }),
}))
