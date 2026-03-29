/**
 * Zustand store for workspace/editor state: renderer handles, WASM loading.
 * Viewport pan/zoom lives in `signals/pointer.ts` (`viewport`). Canvas interaction modes live in XState.
 */

import { create } from 'zustand'
import type { WasmModule } from '../wasm-types'
import { Renderer } from '../index'
import type { WorkerClient } from '../../worker/types'
import { docProxy } from './doc-proxy'
import { querySelectionRect, wasmSelectionRect } from '../signals/selection'

export interface WorkspaceState {
  renderer: Renderer | null
  workerClient: WorkerClient | null

  // WASM Module state
  wasmModule: WasmModule | null
  isWasmModuleLoading: boolean
  wasmModuleError: Error | null

  // Actions
  setRenderer: (renderer: Renderer) => void
  setWorkerClient: (client: WorkerClient | null) => void

  // WASM Module actions
  setWasmModule: (module: WasmModule | null) => void
  setIsWasmModuleLoading: (loading: boolean) => void
  setWasmModuleError: (error: Error | null) => void
}

export const useWorkspaceStore = create<WorkspaceState>()((set) => ({
  renderer: null,
  workerClient: null,
  wasmModule: null,
  isWasmModuleLoading: false,
  wasmModuleError: null,

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
