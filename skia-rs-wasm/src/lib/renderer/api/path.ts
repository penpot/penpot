/**
 * Path content operations
 */

import { parseSVG } from 'svg-path-parser'
import type { WasmModule } from '../wasm-types'
import type { PathContent, PathSegment } from '../types'
import { checkContext } from './context'
import { MAX_BUFFER_CHUNK_SIZE } from './constants'
import { allocBytes, freeBytes, offset8To32 } from '../utils'

/**
 * Segment size constants
 * Must match Rust RawSegmentData: 28 bytes (repr(C, u16) + largest variant)
 */
const SEGMENT_U8_SIZE = 28
const SEGMENT_U32_SIZE = 7 // SEGMENT_U8_SIZE / 4

/** Rust RawSegmentData discriminant (u16 LE) */
const CMD_MOVE_TO = 1
const CMD_LINE_TO = 2
const CMD_CURVE_TO = 3
const CMD_CLOSE = 4

/**
 * Parses an SVG path string (e.g. from exporter content) into PathSegment[].
 * Used when path content is provided as a string (Penpot/Figma path "d" format).
 */
function parsePathStringToSegments(pathString: string): PathSegment[] {
  if (typeof pathString !== 'string' || !pathString.trim()) {
    return []
  }
  try {
    const commands = parseSVG(pathString)
    const segments: PathSegment[] = []
    let lastX = 0
    let lastY = 0
    for (const cmd of commands) {
      const c = (cmd as { command: string; x?: number; y?: number; x1?: number; y1?: number; x2?: number; y2?: number })
      switch (c.command) {
        case 'moveto':
          if (c.x !== undefined && c.y !== undefined) {
            lastX = c.x
            lastY = c.y
            segments.push({ type: 'move-to', x: lastX, y: lastY })
          }
          break
        case 'lineto':
          if (c.x !== undefined && c.y !== undefined) {
            lastX = c.x
            lastY = c.y
            segments.push({ type: 'line-to', x: lastX, y: lastY })
          }
          break
        case 'horizontal lineto':
          lastX = c.x ?? lastX
          segments.push({ type: 'line-to', x: lastX, y: lastY })
          break
        case 'vertical lineto':
          lastY = c.y ?? lastY
          segments.push({ type: 'line-to', x: lastX, y: lastY })
          break
        case 'curveto':
          if (
            c.x !== undefined &&
            c.y !== undefined &&
            c.x1 !== undefined &&
            c.y1 !== undefined &&
            c.x2 !== undefined &&
            c.y2 !== undefined
          ) {
            lastX = c.x
            lastY = c.y
            segments.push({
              type: 'curve-to',
              x: lastX,
              y: lastY,
              c1x: c.x1,
              c1y: c.y1,
              c2x: c.x2,
              c2y: c.y2,
            })
          }
          break
        case 'closepath':
          segments.push({ type: 'close-path' })
          break
        default:
          // Skip unsupported (e.g. quad, arc); could approximate to line/curve
          break
      }
    }
    return segments
  } catch {
    return []
  }
}

/**
 * Normalizes path content to a segment array.
 * Accepts either content.segments or a path string (content or content.content).
 */
function getSegmentsFromContent(content: PathContent): PathSegment[] {
  if (content.segments && Array.isArray(content.segments) && content.segments.length > 0) {
    return content.segments
  }
  const pathString =
    typeof content === 'string' ? content : typeof (content as { content?: string }).content === 'string' ? (content as { content: string }).content : undefined
  if (pathString) {
    return parsePathStringToSegments(pathString)
  }
  return []
}

/**
 * Writes one path segment into buffer at offset, in Rust RawSegmentData layout (28 bytes).
 * Bytes 0-1: tag (u16 LE), 2-3: padding, then variant payload (padding + x,y or c1/c2/end).
 */
function writeSegment(buffer: DataView, offset: number, segment: PathSegment): void {
  // Zero the 28-byte slot so padding is not garbage
  for (let i = 0; i < SEGMENT_U8_SIZE; i++) {
    buffer.setUint8(offset + i, 0)
  }
  switch (segment.type) {
    case 'move-to':
      buffer.setUint16(offset, CMD_MOVE_TO, true)
      buffer.setFloat32(offset + 20, segment.x, true)
      buffer.setFloat32(offset + 24, segment.y, true)
      break
    case 'line-to':
      buffer.setUint16(offset, CMD_LINE_TO, true)
      buffer.setFloat32(offset + 20, segment.x, true)
      buffer.setFloat32(offset + 24, segment.y, true)
      break
    case 'curve-to':
      buffer.setUint16(offset, CMD_CURVE_TO, true)
      buffer.setFloat32(offset + 4, segment.c1x, true)
      buffer.setFloat32(offset + 8, segment.c1y, true)
      buffer.setFloat32(offset + 12, segment.c2x, true)
      buffer.setFloat32(offset + 16, segment.c2y, true)
      buffer.setFloat32(offset + 20, segment.x, true)
      buffer.setFloat32(offset + 24, segment.y, true)
      break
    case 'close-path':
      buffer.setUint16(offset, CMD_CLOSE, true)
      break
  }
}

/**
 * Serializes path content to Uint8Array in WASM RawSegmentData format (28 bytes per segment).
 * Never returns a buffer with invalid segment bytes (avoids Rust panic on invalid enum 0x0).
 */
function serializePathContent(content: PathContent): Uint8Array {
  const segments = getSegmentsFromContent(content)
  if (segments.length === 0) {
    return new Uint8Array(0)
  }
  const buffer = new ArrayBuffer(segments.length * SEGMENT_U8_SIZE)
  const view = new DataView(buffer)
  for (let i = 0; i < segments.length; i++) {
    writeSegment(view, i * SEGMENT_U8_SIZE, segments[i])
  }
  return new Uint8Array(buffer)
}

/**
 * Set shape path content
 * Uploads path content in chunks to WASM
 */
export function setShapePathContent(module: WasmModule, content: PathContent): void {
  checkContext()
  
  // Serialize path content to bytes (28 bytes per segment; Rust expects length multiple of 28)
  const buffer = serializePathContent(content)
  const bufferSize = buffer.length
  
  // Pad to segment chunk size so WASM buffer length is multiple of RAW_SEGMENT_DATA_SIZE (28)
  const paddedSize = bufferSize === 0 ? 0 : Math.ceil(bufferSize / SEGMENT_U8_SIZE) * SEGMENT_U8_SIZE
  const paddedBuffer = paddedSize === 0 ? new Uint8Array(0) : new Uint8Array(paddedSize)
  if (paddedSize > 0) {
    paddedBuffer.set(buffer, 0)
  }
  
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

