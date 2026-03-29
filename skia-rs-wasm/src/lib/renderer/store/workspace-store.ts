/**
 * Zustand store for workspace/editor state: previews, viewport, renderer handles.
 * Canvas interaction modes (move/resize/…) live in `canvasMachine` (XState).
 */

import { create } from 'zustand'
import type { WasmModule } from '../wasm-types'
import type { ViewportData } from '../viewport'
import { Renderer } from '../index'
import type { WorkerClient } from '../../worker/types'
import { docProxy } from './doc-proxy'
import { viewportSignal } from '../signals/pointer'
import { querySelectionRect, wasmSelectionRect } from '../signals/selection'

export interface WorkspaceState {
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
  updateViewport: (data: ViewportData) => void
  setLastAppliedViewport: (data: ViewportData | null) => void
  setRenderer: (renderer: Renderer) => void
  setWorkerClient: (client: WorkerClient | null) => void

  // WASM Module actions
  setWasmModule: (module: WasmModule | null) => void
  setIsWasmModuleLoading: (loading: boolean) => void
  setWasmModuleError: (error: Error | null) => void
}

export const useWorkspaceStore = create<WorkspaceState>()((set) => ({
  viewport: null,
  lastAppliedViewport: null,
  renderer: null,
  workerClient: null,
  wasmModule: null,
  isWasmModuleLoading: false,
  wasmModuleError: null,

  updateViewport: (data) => {
    viewportSignal.value = data
    set({ viewport: data, lastAppliedViewport: data })
  },
  setLastAppliedViewport: (data) => set({ lastAppliedViewport: data }),
  setRenderer: (renderer) => {
    set({ renderer })
    const ids = docProxy.selectedIds
    if (ids.size > 0) {
      wasmSelectionRect.value = querySelectionRect(renderer, ids)
    } else {
      wasmSelectionRect.value = null
    }
  },
  setWorkerClient: (client) => set({ workerClient: client }),
  setWasmModule: (module) => set({ wasmModule: module }),
  setIsWasmModuleLoading: (loading) => set({ isWasmModuleLoading: loading }),
  setWasmModuleError: (error) => set({ wasmModuleError: error }),
}))
