/**
 * Type conversion helpers: UUID/color/u32 and worker selrect/index helpers.
 */

import type { PenpotNode, PenpotPage, Selrect } from '@penpot-exporter/types'
import type { IndexedPage, IndexedShape } from './types'

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
 * Converts a Uint32Array (4 u32 values) back to UUID string format
 */
export function u32ToUUID(buffer: Uint32Array | [number, number, number, number]): string {
  const arr = Array.isArray(buffer) ? buffer : Array.from(buffer)
  const parts = arr.map(val => val.toString(16).padStart(8, '0'))
  return `${parts[0]}${parts[1].slice(0, 4)}-${parts[1].slice(4)}-${parts[2].slice(0, 4)}-${parts[2].slice(4)}${parts[3].slice(0, 4)}-${parts[3].slice(4)}`
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

function flattenChildrenRec(
  nodes: PenpotNode[] | undefined,
  parentId: string | undefined,
  frameId: string | undefined
): { objects: Record<string, IndexedShape> } {
  const objects: Record<string, IndexedShape> = {}
  if (!nodes?.length) return { objects }

  for (const node of nodes) {
    const childList = (node as { children?: PenpotNode[] }).children
    const childIds = childList?.map(c => c.id).filter((id): id is string => id != null) ?? []

    const indexed: IndexedShape = {
      ...node,
      parentId: parentId ?? node.parentId,
      frameId: frameId ?? parentId ?? node.frameId,
      shapes: childIds.length > 0 ? childIds : undefined,
    }
    objects[node.id] = indexed

    if (childList?.length) {
      const resolvedFrameId = node.type === 'frame' ? node.id : frameId ?? parentId
      const childResult = flattenChildrenRec(childList, node.id, resolvedFrameId)
      Object.assign(objects, childResult.objects)
    }
  }
  return { objects }
}

export function flattenPageToIndexed(page: PenpotPage): IndexedPage {
  const children = page.children ?? []
  const rootFrame = children[0]
  if (!rootFrame) {
    return {
      id: page.id ?? ZERO_UUID,
      objects: {},
    }
  }

  const rootChildIds = children.slice(1).map(n => n.id).filter((id): id is string => id != null)
  const rootIndexed: IndexedShape = {
    ...rootFrame,
    parentId: undefined,
    frameId: rootFrame.id,
    shapes: rootChildIds.length > 0 ? rootChildIds : undefined,
  }

  const objects: Record<string, IndexedShape> = {
    [rootFrame.id]: rootIndexed,
  }

  for (let i = 1; i < children.length; i++) {
    const node = children[i]
    const childList = (node as { children?: PenpotNode[] }).children
    const childIds = childList?.map(c => c.id).filter((id): id is string => id != null) ?? []

    const indexed: IndexedShape = {
      ...node,
      parentId: rootFrame.id,
      frameId: rootFrame.id,
      shapes: childIds.length > 0 ? childIds : undefined,
    }
    objects[node.id] = indexed

    if (childList?.length) {
      const resolvedFrameId = node.type === 'frame' ? node.id : rootFrame.id
      const childResult = flattenChildrenRec(childList, node.id, resolvedFrameId)
      Object.assign(objects, childResult.objects)
    }
  }

  return {
    id: page.id ?? ZERO_UUID,
    objects,
  }
}
