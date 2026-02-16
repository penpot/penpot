/**
 * TypeScript declaration file for render-wasm.js
 * This is an Emscripten-generated WASM module
 */

import type { WasmModule } from '../../src/lib/renderer/wasm-types'

export interface WasmModuleOptions {
  locateFile?: (path: string, prefix: string) => string
  [key: string]: any
}

/**
 * Initializes the WASM module
 * @param options - Optional configuration for the WASM module
 * @returns Promise that resolves to the initialized WASM module
 */
declare function initWasmModule(options?: WasmModuleOptions): Promise<WasmModule>

export default initWasmModule