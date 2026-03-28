/**
 * Apply a 2D affine transform matrix to a shape's geometry.
 * Used for move, resize, and rotate commit (propagated result from WASM).
 */

import type { Matrix, PenpotNode } from 'penpot-exporter/types'
import { makeSelrect } from '../types'
import { invertMatrix } from './matrix'

export function applyTransformToNode(
  node: PenpotNode,
  matrix: Matrix
): Partial<PenpotNode> | null {
  const sr = node.selrect
  if (!sr) return null
  const x = sr.x ?? 0
  const y = sr.y ?? 0
  const w = sr.width ?? 0
  const h = sr.height ?? 0
  if (w <= 0 || h <= 0) return null

  const { a: ma, b: mb, c: mc, d: md, e: me, f: mf } = matrix
  const T = node.transform

  const cx = x + w / 2
  const cy = y + h / 2

  const worldCorner = (dx: number, dy: number): { x: number; y: number } => {
    if (!T) return { x: cx + dx, y: cy + dy }
    return {
      x: cx + T.a * dx + T.c * dy,
      y: cy + T.b * dx + T.d * dy,
    }
  }

  const wNw = worldCorner(-w / 2, -h / 2)
  const wNe = worldCorner(w / 2, -h / 2)
  const wSe = worldCorner(w / 2, h / 2)
  const wSw = worldCorner(-w / 2, h / 2)

  const applyM = (p: { x: number; y: number }): { x: number; y: number } => ({
    x: ma * p.x + mc * p.y + me,
    y: mb * p.x + md * p.y + mf,
  })

  const newNw = applyM(wNw)
  const newNe = applyM(wNe)
  const newSe = applyM(wSe)
  const newSw = applyM(wSw)

  const newCx = ma * cx + mc * cy + me
  const newCy = mb * cx + md * cy + mf

  const newWidth = Math.sqrt((newNe.x - newNw.x) ** 2 + (newNe.y - newNw.y) ** 2)
  const newHeight = Math.sqrt((newSw.x - newNw.x) ** 2 + (newSw.y - newNw.y) ** 2)

  if (newWidth <= 0 || newHeight <= 0) return null

  const newX = newCx - newWidth / 2
  const newY = newCy - newHeight / 2
  const selrect = makeSelrect(newX, newY, newWidth, newHeight)

  const hvx = (newNe.x - newNw.x) / newWidth
  const hvy = (newNe.y - newNw.y) / newWidth
  const vvx = (newSw.x - newNw.x) / newHeight
  const vvy = (newSw.y - newNw.y) / newHeight
  const newTransform: Matrix = { a: hvx, b: hvy, c: vvx, d: vvy, e: 0, f: 0 }
  const newTransformInverse = invertMatrix(newTransform)
  const points = [newNw, newNe, newSe, newSw]

  const updates: Partial<PenpotNode> = {
    selrect,
    points,
    transform: newTransform,
    transformInverse: newTransformInverse ?? undefined,
    rotation: Math.atan2(hvy, hvx) * (180 / Math.PI),
  }
  if (typeof node.x === 'number') updates.x = newX
  if (typeof node.y === 'number') updates.y = newY
  if (typeof node.width === 'number') updates.width = newWidth
  if (typeof node.height === 'number') updates.height = newHeight
  return updates
}
