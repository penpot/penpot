/**
 * Core rendering functions
 */

import type { WasmModule } from '../wasm-types'
import { uuidToU32Tuple } from '../types'
import { checkContext, getContextInitialized, getContextLost, getPendingRender, setPendingRender } from './context'

/**
 * Renders with timestamp
 */
export function render(module: WasmModule, timestamp: number): void {
  checkContext()
  module._render(timestamp)
}

/**
 * Synchronous render
 */
export function renderSync(module: WasmModule): void {
  checkContext()
  module._render_sync()
}

/**
 * Render specific shape synchronously
 */
export function renderSyncShape(module: WasmModule, id: string): void {
  checkContext()
  const [a, b, c, d] = uuidToU32Tuple(id)
  module._render_sync_shape(a, b, c, d)
}

/**
 * Request async render via requestAnimationFrame
 */
export function requestRender(module: WasmModule, _requester: string): void {
  if (!getContextInitialized() || getContextLost()) {
    return
  }
  if (getPendingRender()) {
    return
  }

  setPendingRender(true)
  requestAnimationFrame((ts) => {
    setPendingRender(false)
    render(module, ts)
  })
}
