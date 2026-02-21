/**
 * Selection bounds: union of selected nodes' selrects for overlay drawing.
 */

import type { PenpotNode } from '@penpot-exporter/types'

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

/**
 * Compute the union rect of all nodes' selrects. Handles both { x, y, width, height } and { x1, y1, x2, y2 }.
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
    minX = Math.min(minX, rect.x)
    minY = Math.min(minY, rect.y)
    maxX = Math.max(maxX, rect.x + rect.width)
    maxY = Math.max(maxY, rect.y + rect.height)
  }
  if (!hasAny) return null
  const result = {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
  }
  return result
}
