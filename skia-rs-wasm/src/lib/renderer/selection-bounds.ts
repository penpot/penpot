/**
 * Selection bounds: union of selected nodes' selrects for overlay drawing.
 */

import type { PenpotNode } from '@penpot-exporter/types'

type SelrectLike = { x?: number; y?: number; width?: number; height?: number; x1?: number; y1?: number; x2?: number; y2?: number } | null | undefined

function normalizeSelrect(sr: SelrectLike): { x: number; y: number; width: number; height: number } | null {
  if (!sr) return null
  const r = sr as Record<string, unknown>
  const x = (r.x as number) ?? (r.x1 as number)
  const y = (r.y as number) ?? (r.y1 as number)
  const width =
    (r.width as number) ??
    (typeof (r.x2 as number) === 'number' && typeof (r.x1 as number) === 'number' ? (r.x2 as number) - (r.x1 as number) : 0)
  const height =
    (r.height as number) ??
    (typeof (r.y2 as number) === 'number' && typeof (r.y1 as number) === 'number' ? (r.y2 as number) - (r.y1 as number) : 0)
  if (typeof x !== 'number' || typeof y !== 'number' || width <= 0 || height <= 0) return null
  return { x, y, width, height }
}

export interface SelectionBounds {
  x: number
  y: number
  width: number
  height: number
}

/**
 * Compute the union rect of all nodes' selrects. Handles both { x, y, width, height } and { x1, y1, x2, y2 }.
 * Returns null if no valid bounds (no nodes or no valid selrects).
 */
export function getSelectionBounds(nodes: PenpotNode[]): SelectionBounds | null {
  if (!nodes.length) return null
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  let hasAny = false
  for (const node of nodes) {
    const rect = normalizeSelrect(node.selrect as SelrectLike)
    if (!rect) continue
    hasAny = true
    minX = Math.min(minX, rect.x)
    minY = Math.min(minY, rect.y)
    maxX = Math.max(maxX, rect.x + rect.width)
    maxY = Math.max(maxY, rect.y + rect.height)
  }
  if (!hasAny) return null
  return {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
  }
}
