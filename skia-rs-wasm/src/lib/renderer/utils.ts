/**
 * Utility functions for memory management and WASM heap operations.
 * UUID/color conversions re-exported via renderer types (from common).
 */

import type { WasmModule } from "src"
import { uuidToU32 } from './types'

/**
 * Allocates memory in WASM heap and returns the offset
 */
export function allocBytes(module: WasmModule, size: number): number {
  return module._alloc_bytes(size)
}

/**
 * Frees allocated memory in WASM heap
 */
export function freeBytes(module: WasmModule): void {
  module._free_bytes()
}

/**
 * Writes a UUID to WASM heap at the given offset
 */
export function writeUUIDToHeap(
  offset: number,
  heap: Uint32Array,
  id: string | null | undefined
): number {
  const buffer = uuidToU32(id)
  heap.set(buffer, offset)
  return offset + 4 // Return next offset
}

/**
 * Writes a UUID to DataView at the given byte offset
 * Uses little-endian format (true parameter)
 */
export function writeUUIDToDataView(
  dataView: DataView,
  byteOffset: number,
  id: string | null | undefined
): void {
  const buffer = uuidToU32(id)
  // Write 4 u32 values at byte offsets 0, 4, 8, 12 (little-endian)
  dataView.setUint32(byteOffset + 0, buffer[0], true)
  dataView.setUint32(byteOffset + 4, buffer[1], true)
  dataView.setUint32(byteOffset + 8, buffer[2], true)
  dataView.setUint32(byteOffset + 12, buffer[3], true)
}

/**
 * Writes a float32 value to WASM heap at the given offset
 */
export function writeF32ToHeap(offset: number, heap: Float32Array, value: number): number {
  heap[offset] = value
  return offset + 1
}

/**
 * Writes a u32 value to WASM heap at the given offset
 */
export function writeU32ToHeap(offset: number, heap: Uint32Array, value: number): number {
  heap[offset] = value
  return offset + 1
}

/**
 * Gets the device pixel ratio, defaulting to 1
 */
export function getDPR(): number {
  if (typeof window !== 'undefined' && window.devicePixelRatio) {
    return window.devicePixelRatio
  }
  return 1
}

/**
 * Detects the browser type for WASM initialization
 */
export function detectBrowser(): number {
  if (typeof navigator === 'undefined') {
    return 4 // Unknown
  }

  const ua = navigator.userAgent.toLowerCase()
  
  if (ua.includes('firefox')) {
    return 0 // Firefox
  } else if (ua.includes('chrome') && !ua.includes('edg')) {
    return 1 // Chrome
  } else if (ua.includes('safari') && !ua.includes('chrome')) {
    return 2 // Safari
  } else if (ua.includes('edg')) {
    return 3 // Edge
  }
  
  return 4 // Unknown
}

/**
 * Checks if WebGL2 is supported in the current browser
 * @param canvas - Optional canvas element to test with
 * @returns true if WebGL2 is supported, false otherwise
 */
export function isWebGL2Supported(canvas?: HTMLCanvasElement): boolean {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return false
  }

  // Create a temporary canvas if none provided
  const testCanvas = canvas || document.createElement('canvas')
  
  try {
    const gl = testCanvas.getContext('webgl2', {
      alpha: true,
      antialias: false,
      depth: true,
      stencil: true,
      preserveDrawingBuffer: true,
    })
    
    return gl !== null
  } catch (e: unknown) {
    console.error('Error checking WebGL2 support:', e)
    return false
  }
}

/**
 * Converts an 8-bit (byte) offset to a 32-bit (4-byte) offset
 * Divides the value by 4
 */
export function offset8To32(value: number): number {
  return value >> 2
}

/**
 * Calculates allocation size for a collection of items
 * @param count - Number of items
 * @param itemSize - Size of each item in bytes
 */
export function getAllocSize(count: number, itemSize: number): number {
  return count * itemSize
}

/**
 * Writes a matrix (transform) to WASM heap at the given offset
 * Matrix has 6 float32 values: a, b, c, d, e, f
 */
export function writeMatrixToHeap(
  offset: number,
  heap: Float32Array,
  matrix: { a: number; b: number; c: number; d: number; e: number; f: number }
): number {
  heap[offset] = matrix.a
  heap[offset + 1] = matrix.b
  heap[offset + 2] = matrix.c
  heap[offset + 3] = matrix.d
  heap[offset + 4] = matrix.e
  heap[offset + 5] = matrix.f
  return offset + 6
}

/**
 * Writes an i32 value to WASM heap at the given offset
 */
export function writeI32ToHeap(offset: number, heap: Int32Array, value: number): number {
  heap[offset] = value
  return offset + 1
}

/**
 * Writes a u8 (byte) value to WASM heap at the given offset
 */
export function writeU8ToHeap(offset: number, heap: Uint8Array, value: number): void {
  heap[offset] = value & 0xff
}

/**
 * Writes a u16 value to WASM heap at the given offset (little-endian)
 */
export function writeU16ToHeap(offset: number, heap: Uint8Array, value: number): void {
  heap[offset] = value & 0xff
  heap[offset + 1] = (value >>> 8) & 0xff
}

/**
 * Gets a slice of the heap as a new typed array
 */
export function sliceHeap<T extends TypedArray>(heap: T, offset: number, size: number): T {
  return heap.slice(offset, offset + size) as T
}

// Type helper for typed arrays
type TypedArray = Int8Array | Uint8Array | Int16Array | Uint16Array | Int32Array | Uint32Array | Float32Array | Float64Array
/**
 * Simple debounce utility
 */

export function debounce<T extends (...args: any[]) => void>(fn: T, delay: number): T {
  let timeoutId: ReturnType<typeof setTimeout> | null = null
  return ((...args: Parameters<T>) => {
    if (timeoutId) {
      clearTimeout(timeoutId)
    }
    timeoutId = setTimeout(() => {
      fn(...args)
      timeoutId = null
    }, delay)
  }) as T
}
/**
 * Simple throttle utility
 */

export function throttle<T extends (...args: any[]) => void>(fn: T, delay: number): T {
  let lastExecTime = 0
  let timeoutId: ReturnType<typeof setTimeout> | null = null

  return ((...args: Parameters<T>) => {
    const currentTime = Date.now()

    if (currentTime - lastExecTime >= delay) {
      if (timeoutId) {
        clearTimeout(timeoutId)
        timeoutId = null
      }
      fn(...args)
      lastExecTime = currentTime
    } else {
      if (!timeoutId) {
        const remainingTime = delay - (currentTime - lastExecTime)
        timeoutId = setTimeout(() => {
          fn(...args)
          lastExecTime = Date.now()
          timeoutId = null
        }, remainingTime)
      }
    }
  }) as T
}

