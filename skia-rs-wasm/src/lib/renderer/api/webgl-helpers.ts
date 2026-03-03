/**
 * Shared WebGL helper functions
 */

import type { WasmModule } from '../wasm-types'
import { getContextInitialized, getContextLost } from './context'

/** Emscripten GL object shape (currentContext from module.GL) */
interface EmscriptenGL {
  currentContext?: { GLctx: WebGL2RenderingContext | null }
}

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

  const currentCtx = (glObj as EmscriptenGL).currentContext
  if (!currentCtx) {
    return null
  }

  return currentCtx.GLctx as WebGL2RenderingContext | null
}

