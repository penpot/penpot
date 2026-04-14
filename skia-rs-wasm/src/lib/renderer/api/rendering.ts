/**
 * Core rendering functions
 */

import type { WasmModule } from '../wasm-types'
import { uuidToU32Tuple } from '../types'
import { checkContext, getContextInitialized, getContextLost, getPendingRender, setPendingRender } from './context'

// #region DEBUG
const _debugBuf: string[] = []
let _debugTimer: ReturnType<typeof setTimeout> | null = null
function _dbg(msg: string) {
  _debugBuf.push(`[${new Date().toISOString()}] ${msg}`)
  if (!_debugTimer) {
    _debugTimer = setTimeout(() => {
      fetch('/__debug_log', { method: 'POST', body: _debugBuf.join('\n'), keepalive: true }).catch(() => {})
      _debugBuf.length = 0
      _debugTimer = null
    }, 200)
  }
}
let _renderCount = 0
let _renderSyncCount = 0
let _lastRenderTs = 0
let _renderStart = 0
let _lastGlCheck = 0
let _prevTextures = 0
function _checkGl(module: WasmModule) {
  const now = performance.now()
  if (now - _lastGlCheck < 2000) return
  _lastGlCheck = now
  try {
    const glObj = (module as any).GL
    const ctx = glObj?.currentContext?.GLctx as WebGL2RenderingContext | undefined
    if (!ctx) return

    const texCount = glObj.textures ? glObj.textures.filter(Boolean).length : -1
    const changed = texCount !== _prevTextures
    const delta = texCount - _prevTextures
    _prevTextures = texCount

    if (changed || _renderCount % 200 === 0) {
      _dbg(`[DEBUG H5] textures=${texCount} delta=${delta > 0 ? '+' : ''}${delta} renders=${_renderCount}`)
    }
  } catch (e) {
    _dbg(`[DEBUG H5] GL check failed: ${e}`)
  }
}
// #endregion DEBUG

/**
 * Renders with timestamp
 */
export function render(module: WasmModule, timestamp: number): void {
  checkContext()
  // #region DEBUG
  _renderCount++
  const gap = _lastRenderTs ? (timestamp - _lastRenderTs).toFixed(1) : 'first'
  _lastRenderTs = timestamp
  _renderStart = performance.now()
  // #endregion DEBUG
  module._render(timestamp)
  // #region DEBUG
  const elapsed = (performance.now() - _renderStart).toFixed(1)
  if (_renderCount % 30 === 0 || Number(elapsed) > 50) {
    const heapMB = ((module as any).HEAPU8?.length / (1024*1024)).toFixed(1) || '?'
    _dbg(`[DEBUG H6] render #${_renderCount} elapsed=${elapsed}ms gap=${gap}ms heap=${heapMB}MB`)
  }
  _checkGl(module)
  // #endregion DEBUG
}

/**
 * Synchronous render
 */
export function renderSync(module: WasmModule): void {
  checkContext()
  // #region DEBUG
  _renderSyncCount++
  _renderStart = performance.now()
  // #endregion DEBUG
  module._render_sync()
  // #region DEBUG
  const elapsed = (performance.now() - _renderStart).toFixed(1)
  _dbg(`[DEBUG H1/H4] renderSync #${_renderSyncCount} elapsed=${elapsed}ms`)
  // #endregion DEBUG
}

/**
 * Render specific shape synchronously
 */
export function renderSyncShape(module: WasmModule, id: string): void {
  checkContext()
  const [a, b, c, d] = uuidToU32Tuple(id)
  // #region DEBUG
  _renderStart = performance.now()
  // #endregion DEBUG
  module._render_sync_shape(a, b, c, d)
  // #region DEBUG
  const elapsed = (performance.now() - _renderStart).toFixed(1)
  _dbg(`[DEBUG H1] renderSyncShape elapsed=${elapsed}ms`)
  // #endregion DEBUG
}

/**
 * Request async render via requestAnimationFrame
 */
export function requestRender(module: WasmModule, _requester: string): void {
  if (!getContextInitialized() || getContextLost()) {
    // #region DEBUG
    _dbg(`[DEBUG H4] requestRender SKIPPED (init=${getContextInitialized()} lost=${getContextLost()}) requester=${_requester}`)
    // #endregion DEBUG
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

