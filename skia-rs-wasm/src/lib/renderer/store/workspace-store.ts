/**
 * Zustand store for workspace state management.
 * Holds app state: selectedNodes, pageId, selectedIds, selection, viewport, renderer, worker.
 * Document/page data lives in DocumentModel; it pushes selectedNodes here and (for dev) currentPageNodes to workspace-dev-store.
 */

import { create } from 'zustand'
import type { WasmModule } from '../wasm-types'
import type { Viewport } from '../viewport'
import { Renderer } from '../index'
import type { PenpotNode, PenpotPage, Selrect } from '@penpot-exporter/types'
import type { ResizeHandlePosition, WorkerClient} from '../types'
import { getSelectionBounds, type Rect } from '../selection-bounds'

/** Implemented by DocumentModel; used so the store and page-crud can call methods without importing the class. */
export interface IDocumentModel {
  getSelectedNodes(selectedIds: Set<string>): PenpotNode[]
  getPage(id: string): PenpotPage | undefined
  setActivePage(pageId: string): Promise<void>
  addPage(page: PenpotPage): Promise<void>
  commitMove(pageId: string, updatedPage: PenpotPage): Promise<void>
  deletePage(pageId: string): Promise<void>
  addNode(node: PenpotNode): Promise<void>
  updateNode(nodeId: string, updates: Partial<PenpotNode>): Promise<void>
  deleteNode(nodeId: string): Promise<void>
}

export interface WorkspaceState {
  // State
  documentModel: IDocumentModel | null
  selectedNodes: PenpotNode[]
  pageId: string | null
  selectedIds: Set<string>
  /** Union of selected nodes' selrects; set when selection changes, used by overlay. */
  selectionBounds: Rect | null
  selectionRect: Selrect | null
  /** Delta applied during move preview so overlay can follow; null when not moving. */
  movePreviewDelta: { x: number; y: number } | null
  isSelecting: boolean
  isMoving: boolean
  isResizing: boolean
  resizeHandle: ResizeHandlePosition | null
  /** Bounds of the selection during resize preview (so overlay can grow with the node); null when not resizing. */
  resizePreviewBounds: Rect | null
  /** When starting area selection with modifier: append (shift) or remove (shift+mod). */
  areaSelectionAppend: boolean
  areaSelectionRemove: boolean
  viewport: Viewport | null
  /** Viewport used for hit-test; set one frame after apply so it matches the displayed frame. */
  lastAppliedViewport: Viewport | null
  /** Bumped on pan/zoom so UI that reads viewport re-renders (viewport is mutated in place). */
  viewportVersion: number
  renderer: Renderer | null
  workerClient: WorkerClient | null

  // WASM Module state
  wasmModule: WasmModule | null
  isWasmModuleLoading: boolean
  wasmModuleError: Error | null

  // Actions
  setDocumentModel: (model: IDocumentModel | null) => void
  setSelectedNodes: (nodes: PenpotNode[]) => void
  setPageId: (id: string | null) => void
  setSelectedIds: (ids: Set<string>) => void
  setSelectionRect: (rect: Selrect | null) => void
  setMovePreviewDelta: (delta: { x: number; y: number } | null) => void
  setIsSelecting: (is: boolean) => void
  setIsMoving: (is: boolean) => void
  setIsResizing: (is: boolean) => void
  setResizeHandle: (handle: ResizeHandlePosition | null) => void
  setResizePreviewBounds: (bounds: { x: number; y: number; width: number; height: number } | null) => void
  setAreaSelectionMode: (append: boolean, remove: boolean) => void
  setViewport: (viewport: Viewport) => void
  setLastAppliedViewport: (vp: Viewport | null) => void
  bumpViewportVersion: () => void
  setRenderer: (renderer: Renderer) => void
  setWorkerClient: (client: WorkerClient | null) => void
  clearSelection: () => void

  // WASM Module actions
  setWasmModule: (module: WasmModule | null) => void
  setIsWasmModuleLoading: (loading: boolean) => void
  setWasmModuleError: (error: Error | null) => void
}

const EMPTY_NODES: PenpotNode[] = []

export const useWorkspaceStore = create<WorkspaceState>()((set, get) => ({
  documentModel: null,
  selectedNodes: EMPTY_NODES,
  pageId: null,
  selectedIds: new Set(),
  selectionBounds: null,
  selectionRect: null,
  movePreviewDelta: null,
  isSelecting: false,
  isMoving: false,
  isResizing: false,
  resizeHandle: null as ResizeHandlePosition | null,
  resizePreviewBounds: null,
  areaSelectionAppend: false,
  areaSelectionRemove: false,
  viewport: null,
  lastAppliedViewport: null,
  viewportVersion: 0,
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
  setMovePreviewDelta: (delta) => set({ movePreviewDelta: delta }),
  setIsSelecting: (is) => set({ isSelecting: is }),
  setIsMoving: (is) => set({ isMoving: is }),
  setIsResizing: (is) => set({ isResizing: is }),
  setResizeHandle: (handle) => set({ resizeHandle: handle }),
  setResizePreviewBounds: (bounds) => set({ resizePreviewBounds: bounds }),
  setAreaSelectionMode: (append, remove) => set({ areaSelectionAppend: append, areaSelectionRemove: remove }),
  setViewport: (viewport) => set((s) => ({
    viewport,
    lastAppliedViewport: viewport && viewport !== s.viewport ? viewport.clone() : s.lastAppliedViewport,
  })),
  setLastAppliedViewport: (vp) => set({ lastAppliedViewport: vp }),
  bumpViewportVersion: () => set((s) => ({ viewportVersion: s.viewportVersion + 1 })),
  setRenderer: (renderer) => set({ renderer }),
  setWorkerClient: (client) => set({ workerClient: client }),
  clearSelection: () => set({
    selectedIds: new Set(),
    selectionBounds: null,
    selectionRect: null,
    selectedNodes: EMPTY_NODES,
  }),
  setWasmModule: (module) => set({ wasmModule: module }),
  setIsWasmModuleLoading: (loading) => set({ isWasmModuleLoading: loading }),
  setWasmModuleError: (error) => set({ wasmModuleError: error }),
}))
