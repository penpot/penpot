/**
 * Shared WebGL helper functions
 */

import type { WasmModule } from '../wasm-types'
import { getContextInitialized, getContextLost } from './context'

/**
 * Gets the WebGL context from the WASM module
 */
export function getWebGLContext(module: WasmModule): WebGL2RenderingContext | null {
  if (!getContextInitialized() || getContextLost()) {
    return null
  }
  
  const glObj = module.GL
  if (!glObj) {
    return null
  }
  
  // Get the current WebGL context from Emscripten
  const currentCtx = (glObj as any).currentContext
  if (!currentCtx) {
    return null
  }
  
  return currentCtx.GLctx as WebGL2RenderingContext | null
}

