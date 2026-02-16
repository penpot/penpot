/**
 * Utility functions for UUID conversion, color conversion, and memory management
 */

/**
 * Converts a UUID string to a Uint32Array of 4 elements
 * UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
 */
export function uuidToU32(id: string | null | undefined): Uint32Array {
  if (!id) {
    // Return zero UUID
    return new Uint32Array([0, 0, 0, 0])
  }

  // Remove dashes and convert to hex
  const hex = id.replace(/-/g, '')
  const buffer = new Uint32Array(4)

  // Each UUID segment is 8 hex characters = 32 bits
  for (let i = 0; i < 4; i++) {
    buffer[i] = parseInt(hex.slice(i * 8, (i + 1) * 8), 16)
  }

  return buffer
}

/**
 * Converts a UUID string to a tuple of 4 u32 values for WASM calls
 */
export function uuidToU32Tuple(id: string | null | undefined): [number, number, number, number] {
  const buffer = uuidToU32(id)
  return [buffer[0], buffer[1], buffer[2], buffer[3]]
}

/**
 * Converts a hex color string to ARGB u32 format
 * @param hex - Hex color string (e.g., "#FF0000" or "FF0000")
 * @param opacity - Opacity value (0-1), defaults to 1
 * @returns ARGB as u32 number
 */
export function hexToU32ARGB(hex: string, opacity: number = 1): number {
  // Remove # if present
  const cleanHex = hex.replace('#', '')
  
  // Parse RGB
  const r = parseInt(cleanHex.slice(0, 2), 16)
  const g = parseInt(cleanHex.slice(2, 4), 16)
  const b = parseInt(cleanHex.slice(4, 6), 16)
  const a = Math.round(opacity * 255)

  // ARGB format: A << 24 | R << 16 | G << 8 | B
  return (a << 24) | (r << 16) | (g << 8) | b
}

/**
 * Converts a Color object to ARGB u32 format
 */
export function colorToU32ARGB(color: { color: string; opacity?: number }): number {
  return hexToU32ARGB(color.color, color.opacity ?? 1)
}

/**
 * Allocates memory in WASM heap and returns the offset
 */
export function allocBytes(module: any, size: number): number {
  return module._alloc_bytes(size)
}

/**
 * Frees allocated memory in WASM heap
 */
export function freeBytes(module: any): void {
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
  } catch (e) {
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

/**
 * Converts a Uint32Array (4 u32 values) back to UUID string format
 * @param buffer - Uint32Array with 4 elements or array of 4 numbers
 * @returns UUID string in format xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
 */
export function u32ToUUID(buffer: Uint32Array | [number, number, number, number]): string {
  const arr = Array.isArray(buffer) ? buffer : Array.from(buffer)
  // Convert each u32 to 8 hex characters, pad with zeros
  const parts = arr.map(val => val.toString(16).padStart(8, '0'))
  // Format as UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  return `${parts[0]}${parts[1].slice(0, 4)}-${parts[1].slice(4)}-${parts[2].slice(0, 4)}-${parts[2].slice(4)}${parts[3].slice(0, 4)}-${parts[3].slice(4)}`
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

