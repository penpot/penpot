/**
 * Zustand store for workspace state management
 * Single source of truth for document, pageMap, selection, viewport, and renderer
 */

import { create } from 'zustand'
import type { WasmModule } from '../wasm-types'
import type { Viewport } from '../viewport'
import { Renderer } from '../index'
import type { WorkerClient } from '../types'
import type { PenpotDocument, PenpotNode, PenpotPage, Selrect } from '@penpot-exporter/types'

export interface WorkspaceState {
  // State
  document: PenpotDocument | null
  pageMap: Map<string, PenpotPage>
  pageId: string | null
  selectedIds: Set<string>
  selectionRect: Selrect | null
  isSelecting: boolean
  isMoving: boolean
  /** When starting area selection with modifier: append (shift) or remove (shift+mod). */
  areaSelectionAppend: boolean
  areaSelectionRemove: boolean
  viewport: Viewport | null
  /** Bumped on pan/zoom so UI that reads viewport re-renders (viewport is mutated in place). */
  viewportVersion: number
  renderer: Renderer | null
  workerClient: WorkerClient | null

  // WASM Module state
  wasmModule: WasmModule | null
  isWasmModuleLoading: boolean
  wasmModuleError: Error | null

  // Actions
  setPageId: (id: string | null) => void
  setSelectedIds: (ids: Set<string>) => void
  setSelectionRect: (rect: Selrect | null) => void
  setIsSelecting: (is: boolean) => void
  setIsMoving: (is: boolean) => void
  setAreaSelectionMode: (append: boolean, remove: boolean) => void
  setViewport: (viewport: Viewport) => void
  /** Call after mutating viewport in place to refresh UI (zoom/pan display). */
  bumpViewportVersion: () => void
  setRenderer: (renderer: Renderer) => void
  setWorkerClient: (client: WorkerClient | null) => void
  clearSelection: () => void
  
  // WASM Module actions
  setWasmModule: (module: WasmModule | null) => void
  setIsWasmModuleLoading: (loading: boolean) => void
  setWasmModuleError: (error: Error | null) => void
}

export const useWorkspaceStore = create<WorkspaceState>()((set) => ({
  document: null,
  pageMap: new Map(),
  pageId: null,
  selectedIds: new Set(),
  selectionRect: null,
  isSelecting: false,
  isMoving: false,
  areaSelectionAppend: false,
  areaSelectionRemove: false,
  viewport: null,
  viewportVersion: 0,
  renderer: null,
  workerClient: null,
  wasmModule: null,
  isWasmModuleLoading: false,
  wasmModuleError: null,

  setPageId: (id) => set({ pageId: id }),
  setSelectedIds: (ids) => set({ selectedIds: ids }),
  setSelectionRect: (rect) => set({ selectionRect: rect }),
  setIsSelecting: (is) => set({ isSelecting: is }),
  setIsMoving: (is) => set({ isMoving: is }),
  setAreaSelectionMode: (append, remove) => set({ areaSelectionAppend: append, areaSelectionRemove: remove }),
  setViewport: (viewport) => set({ viewport }),
  bumpViewportVersion: () => set((s) => ({ viewportVersion: s.viewportVersion + 1 })),
  setRenderer: (renderer) => set({ renderer }),
  setWorkerClient: (client) => set({ workerClient: client }),
  clearSelection: () => set({ selectedIds: new Set(), selectionRect: null }),
  setWasmModule: (module) => set({ wasmModule: module }),
  setIsWasmModuleLoading: (loading) => set({ isWasmModuleLoading: loading }),
  setWasmModuleError: (error) => set({ wasmModuleError: error }),
}))

const EMPTY_NODES: PenpotNode[] = []

/** Flatten page tree (children + nested children) into id -> node map. */
export function flattenPageNodes(page: PenpotPage | null | undefined): Record<string, PenpotNode> {
  const acc: Record<string, PenpotNode> = {}
  if (!page?.children?.length) return acc
  function walk(nodes: PenpotNode[]) {
    for (const node of nodes) {
      acc[node.id] = node
      const childList = (node as { children?: PenpotNode[] }).children
      if (childList?.length) walk(childList)
    }
  }
  walk(page.children)
  return acc
}

export const selectCurrentPageNodes = (state: WorkspaceState): PenpotNode[] =>
  state.pageMap.get(state.pageId ?? '')?.children ?? EMPTY_NODES

/** Selected nodes array derived from selectedIds and current page. Order matches selectedIds iterator. */
export const selectSelectedNodes = (state: WorkspaceState): PenpotNode[] => {
  const page = state.pageMap.get(state.pageId ?? '')
  const byId = flattenPageNodes(page)
  const result: PenpotNode[] = []
  for (const id of state.selectedIds) {
    const node = byId[id]
    if (node) result.push(node)
  }
  return result
}
