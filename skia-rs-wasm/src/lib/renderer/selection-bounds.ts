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
  const result = {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
  }
  // #region agent log
  const firstRect = nodes.length ? normalizeSelrect(nodes[0].selrect as SelrectLike) : null
  if (typeof fetch !== 'undefined') {
    fetch('http://127.0.0.1:7244/ingest/f0136137-81f1-4f6e-a7b5-217ac99b12a5', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        location: 'selection-bounds.ts:getSelectionBounds',
        message: 'selection bounds computed',
        data: { hypothesisId: 'B', nodeCount: nodes.length, bounds: result, firstNodeSelrect: firstRect },
        timestamp: Date.now(),
      }),
    }).catch(() => {})
  }
  // #endregion
  return result
}
