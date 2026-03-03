/**
 * Focus mode operations
 */

import type { WasmModule } from '../wasm-types'
import {
  allocBytes,
  freeBytes,
  writeUUIDToHeap,
  offset8To32,
  getAllocSize,
} from '../utils'
import { checkContext } from './context'
import { UUID_U8_SIZE } from './constants'
import { requestRender } from './rendering'

/**
 * Set focus mode
 */
export function setFocusMode(module: WasmModule, entries: string[]): void {
  checkContext()
  if (entries.length === 0) {
    return
  }

  const size = getAllocSize(entries.length, UUID_U8_SIZE)
  const offset = offset8To32(allocBytes(module, size))
  const heap = module.HEAPU32

  let currentOffset = offset
  for (const id of entries) {
    currentOffset = writeUUIDToHeap(currentOffset, heap, id)
  }

  module._set_focus_mode()
  freeBytes(module)
  requestRender(module, 'set-focus-mode')
}

/**
 * Clear focus mode
 */
export function clearFocusMode(module: WasmModule): void {
  checkContext()
  module._clear_focus_mode()
  requestRender(module, 'clear-focus-mode')
}

