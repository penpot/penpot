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

export const selectCurrentPageNodes = (state: WorkspaceState): PenpotNode[] =>
  state.pageMap.get(state.pageId ?? '')?.children ?? EMPTY_NODES
