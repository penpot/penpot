/**
 * Selection rect preview math for move/rotate drags (overlay sync with WASM modifiers).
 */

import type { SelectionRectResult } from '../types'

export function finiteSelectionRect(r: SelectionRectResult | null): r is SelectionRectResult {
  return (
    r != null &&
    Number.isFinite(r.width) &&
    Number.isFinite(r.height) &&
    Number.isFinite(r.center.x) &&
    Number.isFinite(r.center.y) &&
    Number.isFinite(r.transform.a) &&
    Number.isFinite(r.transform.b) &&
    Number.isFinite(r.transform.c) &&
    Number.isFinite(r.transform.d)
  )
}

export function cloneSelectionRect(sel: SelectionRectResult): SelectionRectResult {
  return {
    width: sel.width,
    height: sel.height,
    center: { x: sel.center.x, y: sel.center.y },
    transform: { ...sel.transform },
  }
}

export function translateSelectionRectWorld(
  sel: SelectionRectResult,
  dx: number,
  dy: number,
): SelectionRectResult {
  return {
    width: sel.width,
    height: sel.height,
    center: { x: sel.center.x + dx, y: sel.center.y + dy },
    transform: { ...sel.transform },
  }
}

/** Rotate selection bounds in world space around pivot (px, py) by deltaDeg (same convention as WASM rotation modifier). */
export function rotateSelectionRectAroundPivot(
  sel: SelectionRectResult,
  px: number,
  py: number,
  deltaDeg: number,
): SelectionRectResult {
  const theta = (deltaDeg * Math.PI) / 180
  const cos = Math.cos(theta)
  const sin = Math.sin(theta)
  const { center, transform, width, height } = sel
  const dx = center.x - px
  const dy = center.y - py
  const nx = px + cos * dx - sin * dy
  const ny = py + sin * dx + cos * dy
  const { a: a0, b: b0, c: c0, d: d0 } = transform
  return {
    width,
    height,
    center: { x: nx, y: ny },
    transform: {
      ...transform,
      a: cos * a0 - sin * b0,
      c: cos * c0 - sin * d0,
      b: sin * a0 + cos * b0,
      d: sin * c0 + cos * d0,
    },
  }
}
