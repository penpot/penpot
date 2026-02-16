/**
 * Boolean operations
 */

import type { WasmModule } from '../wasm-types'
import type { BoolType, PenpotNode, PathContent } from '../types'
import {
  allocBytes,
  freeBytes,
  writeUUIDToHeap,
  offset8To32,
  getAllocSize,
  sliceHeap,
} from '../utils'
import { translateBoolType } from './serializers'
import { checkContext } from './context'
import { UUID_U8_SIZE } from './constants'
import { useShape } from './shape'
import { setObject } from './orchestration'
import { pathFromBytes } from './path'

/**
 * Segment size constants
 */
const SEGMENT_U32_SIZE = 7 // 28 bytes / 4

/**
 * Gets all children including nested children for a shape
 */
function getAllChildrenWithSelf(objects: Record<string, PenpotNode>, id: string): PenpotNode[] {
  const result: PenpotNode[] = []
  const visited = new Set<string>()

  function collectChildren(currentId: string): void {
    if (visited.has(currentId)) {
      return
    }
    visited.add(currentId)

    const node = objects[currentId]
    if (!node) {
      return
    }

    result.push(node)

    if (node.shapes) {
      for (const childId of node.shapes) {
        collectChildren(childId)
      }
    }
  }

  collectChildren(id)
  return result
}

/**
 * Reads path content from heap
 * Path format: length (u32) followed by segments (each segment is 7 u32 values = 28 bytes)
 * Matches ClojureScript implementation
 */
function readPathContentFromHeap(
  module: WasmModule,
  heap: Uint32Array,
  offset: number
): PathContent {
  const length = heap[offset]
  const data = sliceHeap(heap, offset + 1, length * SEGMENT_U32_SIZE)
  const heapF32 = module.HEAPF32

  // Call pathFromBytes with the sliced data and base offset for reading floats
  // baseOffset is offset + 1 (skip the length field)
  return pathFromBytes(data, heapF32, offset + 1)
}

/**
 * Calculate boolean operation
 */
export function calculateBool(
  module: WasmModule,
  shape: { 'bool-type': BoolType; shapes: string[] },
  objects: Record<string, PenpotNode>
): PathContent | null {
  checkContext(module)

  // Start temp objects
  module._start_temp_objects()

  try {
    const boolType = translateBoolType(shape.boolType)
    const ids = shape.shapes

    // Get all children including nested children
    const allChildren: PenpotNode[] = []
    for (const id of ids) {
      allChildren.push(...getAllChildrenWithSelf(objects, id))
    }

    // Initialize shapes pool
    module._init_shapes_pool(allChildren.length)

    // Serialize all children
    for (const child of allChildren) {
      setObject(module, child)
    }

    // Write shape IDs to heap (in reverse order)
    const size = getAllocSize(ids.length, UUID_U8_SIZE)
    const offset = offset8To32(allocBytes(module, size))
    const heap = module.HEAPU32

    let currentOffset = offset
    for (let i = ids.length - 1; i >= 0; i--) {
      currentOffset = writeUUIDToHeap(currentOffset, heap, ids[i])
    }

    // Calculate bool and get result
    const resultOffset = offset8To32(module._calculate_bool(boolType))
    const pathContent = readPathContentFromHeap(module, heap, resultOffset)

    freeBytes(module)

    return pathContent
  } finally {
    // End temp objects
    module._end_temp_objects()
  }
}

/**
 * Convert shape to path
 */
export function shapeToPath(module: WasmModule, id: string): PathContent {
  checkContext(module)
  useShape(module, id)
  const offset = offset8To32(module._current_to_path())
  const heap = module.HEAPU32
  
  // Read path content from heap
  const pathContent = readPathContentFromHeap(module, heap, offset)

  freeBytes(module)
  return pathContent
}

