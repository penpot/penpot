/**
 * Type conversion helpers: UUID/color/u32 and selrect.
 */

import type { Selrect } from 'penpot-exporter/types'

export const ZERO_UUID = '00000000-0000-0000-0000-000000000000'

/**
 * Converts a UUID string to a Uint32Array of 4 elements
 * UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
 */
export function uuidToU32(id: string | null | undefined): Uint32Array {
  if (!id) {
    return new Uint32Array([0, 0, 0, 0])
  }
  const hex = id.replace(/-/g, '')
  const buffer = new Uint32Array(4)
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
 */
export function hexToU32ARGB(hex: string, opacity: number = 1): number {
  const cleanHex = hex.replace('#', '')
  const r = parseInt(cleanHex.slice(0, 2), 16)
  const g = parseInt(cleanHex.slice(2, 4), 16)
  const b = parseInt(cleanHex.slice(4, 6), 16)
  const a = Math.round(opacity * 255)
  return (a << 24) | (r << 16) | (g << 8) | b
}

/**
 * Converts a Color object to ARGB u32 format
 */
export function colorToU32ARGB(color: { color: string; opacity?: number }): number {
  return hexToU32ARGB(color.color, color.opacity ?? 1)
}

/**
 * Converts a Uint32Array (4 u32 values) back to UUID string format (8-4-4-4-12).
 */
export function u32ToUUID(buffer: Uint32Array | [number, number, number, number]): string {
  const arr = Array.isArray(buffer) ? buffer : Array.from(buffer)
  const parts = arr.map(val => val.toString(16).padStart(8, '0'))
  return `${parts[0]}-${parts[1].slice(0, 4)}-${parts[1].slice(4)}-${parts[2].slice(0, 4)}-${parts[2].slice(4)}${parts[3]}`
}

/** Build a full Selrect from origin and size */
export function makeSelrect(x: number, y: number, width: number, height: number): Selrect {
  return {
    x,
    y,
    width,
    height,
    x1: x,
    y1: y,
    x2: x + width,
    y2: y + height,
  }
}
