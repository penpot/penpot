/**
 * Path content operations
 */

import type { WasmModule } from '../wasm-types'
import type { PathContent, PathSegment } from '../types'
import { checkContext } from './context'
import { MAX_BUFFER_CHUNK_SIZE } from './constants'
import { allocBytes, freeBytes, offset8To32 } from '../utils'

/**
 * Segment size constants
 * Each segment is 28 bytes (7 u32 values)
 */
const SEGMENT_U8_SIZE = 28
const SEGMENT_U32_SIZE = 7 // SEGMENT_U8_SIZE / 4

/**
 * Estimates the byte size of path content
 * This is a simplified estimation - actual implementation would need proper path format
 */
function estimatePathByteSize(content: PathContent): number {
  // Basic estimation: assume each segment is ~32 bytes
  // This is a placeholder - actual size calculation would depend on path format
  if (content.segments && Array.isArray(content.segments)) {
    return content.segments.length * 32
  }
  // Fallback: estimate based on content structure
  return 128
}

/**
 * Serializes path content to Uint8Array
 * This is a simplified serializer - actual implementation would need proper path format
 */
function serializePathContent(content: PathContent): Uint8Array {
  // This is a placeholder implementation
  // Actual path serialization would need to match the WASM path format
  // For now, create a minimal buffer that can be extended
  const estimatedSize = estimatePathByteSize(content)
  const buffer = new Uint8Array(estimatedSize)
  
  // TODO: Implement proper path serialization based on path format
  // This would serialize segments, commands, coordinates, etc.
  
  return buffer
}

/**
 * Set shape path content
 * Uploads path content in chunks to WASM
 */
export function setShapePathContent(module: WasmModule, content: PathContent): void {
  checkContext()
  
  // Serialize path content to bytes
  const buffer = serializePathContent(content)
  const bufferSize = buffer.length
  
  // Pad to 4-byte alignment
  const paddedSize = Math.ceil(bufferSize / 4) * 4
  const paddedBuffer = new Uint8Array(paddedSize)
  paddedBuffer.set(buffer, 0)
  
  // Start path buffer
  module._start_shape_path_buffer()
  
  // Upload in chunks
  const chunkSize = Math.floor(MAX_BUFFER_CHUNK_SIZE / 4) // Size in u32 units
  const heapU32 = module.HEAPU32
  
  let offset = 0
  while (offset < paddedSize) {
    const end = Math.min(paddedSize, offset + chunkSize * 4)
    const chunk = paddedBuffer.subarray(offset, end)
    
    // Convert chunk to Uint32Array
    const chunkU32 = new Uint32Array(
      chunk.buffer,
      chunk.byteOffset,
      Math.floor(chunk.length / 4)
    )
    
    // Allocate memory for chunk
    const heapOffset = offset8To32(allocBytes(module, chunkU32.length * 4))
    
    // Copy chunk to heap
    heapU32.set(chunkU32, heapOffset)
    
    // Send chunk to WASM
    module._set_shape_path_chunk_buffer()
    
    // Free chunk memory (will be freed by WASM, but we need to track allocation)
    freeBytes(module)
    
    offset = end
  }
  
  // Finalize path buffer
  module._set_shape_path_buffer()
}

/**
 * Deserializes path content from binary data
 * 
 * @param data - Uint32Array containing segment data (already sliced from heap)
 * @param heapF32 - Float32Array view of the heap for reading float coordinates
 * @param baseOffset - Base offset in u32 units where the data starts in the heap
 * @returns PathContent with deserialized segments
 */
export function pathFromBytes(
  data: Uint32Array,
  heapF32: Float32Array,
  baseOffset: number
): PathContent {
  const segmentCount = data.length / SEGMENT_U32_SIZE
  const segments: PathSegment[] = []

  // Get the underlying ArrayBuffer to read u16 command type
  const buffer = data.buffer
  const dataView = new DataView(buffer, data.byteOffset, data.byteLength)

  for (let i = 0; i < segmentCount; i++) {
    // Calculate offsets
    const segmentByteOffset = i * SEGMENT_U8_SIZE
    const segmentU32Offset = baseOffset + i * SEGMENT_U32_SIZE

    // Read command type (u16) from bytes 0-1 of the segment
    const commandType = dataView.getUint16(segmentByteOffset, true) // little-endian

    switch (commandType) {
      case 1: // MoveTo
        {
          // Read x, y from bytes 20-27 (as f32)
          // In heapF32, offset 20 bytes = 5 u32, offset 24 bytes = 6 u32
          const x = heapF32[segmentU32Offset + 5]
          const y = heapF32[segmentU32Offset + 6]
          segments.push({
            type: 'move-to',
            x,
            y,
          })
        }
        break

      case 2: // LineTo
        {
          const x = heapF32[segmentU32Offset + 5]
          const y = heapF32[segmentU32Offset + 6]
          segments.push({
            type: 'line-to',
            x,
            y,
          })
        }
        break

      case 3: // CurveTo
        {
          // Read control points and end point
          // c1x at offset 4 bytes = 1 u32, c1y at 8 bytes = 2 u32, etc.
          const c1x = heapF32[segmentU32Offset + 1]
          const c1y = heapF32[segmentU32Offset + 2]
          const c2x = heapF32[segmentU32Offset + 3]
          const c2y = heapF32[segmentU32Offset + 4]
          const x = heapF32[segmentU32Offset + 5]
          const y = heapF32[segmentU32Offset + 6]
          segments.push({
            type: 'curve-to',
            x,
            y,
            c1x,
            c1y,
            c2x,
            c2y,
          })
        }
        break

      case 4: // Close
        segments.push({
          type: 'close-path',
        })
        break

      default:
        // Unknown command type, skip
        break
    }
  }

  return {
    segments,
  }
}

