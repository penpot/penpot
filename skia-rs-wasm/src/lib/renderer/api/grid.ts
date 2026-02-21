/**
 * Grid display operations
 */

import type { WasmModule } from '../wasm-types'
import { uuidToU32Tuple } from '@skia-rs-wasm/common'
import { freeBytes, offset8To32 } from '../utils'
import { checkContext } from './context'
import { requestRender } from './rendering'

/**
 * Show grid
 */
export function showGrid(module: WasmModule, id: string): void {
  checkContext(module)
  const [a, b, c, d] = uuidToU32Tuple(id)
  module._show_grid(a, b, c, d)
  requestRender(module, 'show-grid')
}

/**
 * Clear grid
 */
export function clearGrid(module: WasmModule): void {
  checkContext(module)
  module._hide_grid()
  requestRender(module, 'clear-grid')
}

/**
 * Get grid coordinates
 */
export function getGridCoords(module: WasmModule, position: { x: number; y: number }): [number, number] {
  checkContext(module)
  const offset = module._get_grid_coords(position.x, position.y)
  const heapI32 = module.HEAP32
  const offset32 = offset8To32(offset)

  const row = heapI32[offset32]
  const column = heapI32[offset32 + 1]

  freeBytes(module)
  return [row, column]
}

