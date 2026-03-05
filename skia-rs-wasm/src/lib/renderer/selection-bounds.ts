/**
 * Selection bounds: union of selected nodes' selrects for overlay drawing.
 */

import type { PenpotNode } from 'penpot-exporter/types'

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

/**
 * Returns the axis-aligned bounding box of a rectangle rotated around its center.
 * If rotation is missing or 0, returns the original rect.
 */
export function getAABBOfRotatedRect(
  x: number,
  y: number,
  width: number,
  height: number,
  rotationDeg: number | undefined
): Rect {
  if (rotationDeg === undefined || rotationDeg === 0) {
    return { x, y, width, height }
  }
  const cx = x + width / 2
  const cy = y + height / 2
  const theta = (rotationDeg * Math.PI) / 180
  const cos = Math.cos(theta)
  const sin = Math.sin(theta)
  const rotate = (px: number, py: number) => ({
    x: cx + (px - cx) * cos - (py - cy) * sin,
    y: cy + (px - cx) * sin + (py - cy) * cos,
  })
  const corners = [
    rotate(x, y),
    rotate(x + width, y),
    rotate(x + width, y + height),
    rotate(x, y + height),
  ]
  const minX = Math.min(...corners.map((p) => p.x))
  const minY = Math.min(...corners.map((p) => p.y))
  const maxX = Math.max(...corners.map((p) => p.x))
  const maxY = Math.max(...corners.map((p) => p.y))
  return {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
  }
}

/**
 * Compute the union rect of all nodes' selrects. Handles both { x, y, width, height } and { x1, y1, x2, y2 }.
 * When a node has rotation, uses the AABB of the rotated selrect so the selection box encompasses the shape.
 * Returns null if no valid bounds (no nodes or no valid selrects).
 */
export function getSelectionBounds(nodes: PenpotNode[]): Rect | null {
  if (!nodes.length) return null
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  let hasAny = false
  for (const node of nodes) {
    const rect = node.selrect
    if (!rect) continue
    hasAny = true
    const x = rect.x ?? 0
    const y = rect.y ?? 0
    const w = rect.width ?? 0
    const h = rect.height ?? 0
    const rotation = (node as { rotation?: number }).rotation
    const effective = getAABBOfRotatedRect(x, y, w, h, rotation)
    minX = Math.min(minX, effective.x)
    minY = Math.min(minY, effective.y)
    maxX = Math.max(maxX, effective.x + effective.width)
    maxY = Math.max(maxY, effective.y + effective.height)
  }
  if (!hasAny) return null
  return {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
  }
}
