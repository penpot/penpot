/**
 * Viewport management
 */

import type { WasmModule } from '../wasm-types'
import type { PenpotNode } from '@penpot-exporter/types'
import { hexToU32ARGB } from '../utils'
import { checkContext, getContextInitialized, getContextLost } from './context'
import { throttle } from '../utils'
import { debounce } from '../utils'
import { DEBOUNCE_DELAY_MS, THROTTLE_DELAY_MS } from './constants'
import { render } from './rendering'
import { setObjects } from './orchestration'

/**
 * Render finish with debounce
 * Calls _set_view_end and then renders with debounce
 */
export const renderFinish = debounce((module: WasmModule, timestamp: number) => {
  if (getContextInitialized() && !getContextLost()) {
    module._set_view_end()
    render(module, timestamp)
  }
}, DEBOUNCE_DELAY_MS)

/**
 * Render pan with throttle
 * Throttles render calls for pan operations
 */
const renderPan = throttle((module: WasmModule, timestamp: number) => {
  if (getContextInitialized() && !getContextLost()) {
    render(module, timestamp)
  }
}, THROTTLE_DELAY_MS)

/**
 * Set view box with pan and zoom
 */
export function setViewBox(
  module: WasmModule,
  prevZoom: number,
  zoom: number,
  vbox: { x: number; y: number }
): void {
  checkContext(module)
  const isPan = Math.abs(prevZoom - zoom) < 0.001

  module._set_view_start()
  module._set_view(zoom, -vbox.x, -vbox.y)

  if (isPan) {
    // Pan: throttle render
    renderPan(module, performance.now())
    renderFinish(module, performance.now())
  } else {
    // Zoom: render from cache
    module._render_from_cache(0)
    renderFinish(module, performance.now())
  }
}

/**
 * Initialize viewport
 */
export async function initializeViewport(
  module: WasmModule,
  baseObjects: Record<string, PenpotNode>,
  zoom: number,
  vbox: { x: number; y: number },
  background: string,
  callback?: () => void
): Promise<void> {
  checkContext(module)
  const rgba = hexToU32ARGB(background, 1)

  module._set_canvas_background(rgba)
  module._set_view(zoom, -vbox.x, -vbox.y)

  // Initialize shapes pool before setting objects
  const shapes = Object.values(baseObjects)
  const totalShapes = shapes.length
  module._init_shapes_pool(totalShapes)

  // Serialize all objects
  await setObjects(module, baseObjects, callback || (() => {
    renderFinish(module, performance.now())
  }))
}

/**
 * Resize viewbox
 */
export function resizeViewbox(module: WasmModule, width: number, height: number): void {
  checkContext(module)
  module._resize_viewbox(width, height)
}

