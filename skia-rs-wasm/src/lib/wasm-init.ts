/**
 * Public API for WASM module initialization
 * Initializes the module and stores it in the Zustand store for reactivity
 */

import type { WasmModule } from './renderer/wasm-types'
import { ensureWasmModule, resetWasmModuleInternal } from './renderer/wasm-module'
import { useWorkspaceStore } from './renderer/store/workspace-store'

/**
 * Initialize the WASM module and store it in the Zustand store.
 * If already initialized, returns the existing module.
 * 
 * @param wasmPath - Optional path to render-wasm.js. Defaults to '/wasm/render-wasm.js'
 * @returns Promise that resolves to the WASM module
 */
export async function initWasmModule(wasmPath?: string): Promise<WasmModule> {
  const store = useWorkspaceStore.getState()
  
  // Set loading state
  store.setIsWasmModuleLoading(true)
  store.setWasmModuleError(null)
  
  try {
    // Load module using internal singleton
    const module = await ensureWasmModule(wasmPath)
    
    // Update store with loaded module
    store.setWasmModule(module)
    store.setIsWasmModuleLoading(false)

    console.log('WASM module initialized', module)
    
    return module
  } catch (error) {
    const err = error instanceof Error ? error : new Error(String(error))
    store.setWasmModuleError(err)
    store.setIsWasmModuleLoading(false)
    throw err
  }
}

/**
 * Check if WASM module is ready (loaded in store)
 */
export function isWasmModuleReady(): boolean {
  return useWorkspaceStore.getState().wasmModule !== null
}

/**
 * Get WASM module from store
 */
export function getWasmModule(): WasmModule | null {
  return useWorkspaceStore.getState().wasmModule
}

/**
 * Reset WASM module (clears store and internal singleton)
 * WARNING: This will break any existing Renderer instances
 */
export function resetWasmModule(): void {
  // Clear internal singleton
  resetWasmModuleInternal()
  
  // Clear store
  const store = useWorkspaceStore.getState()
  store.setWasmModule(null)
  store.setIsWasmModuleLoading(false)
  store.setWasmModuleError(null)
}

