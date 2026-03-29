import type { SelectionRectResult } from '../../renderer/types'

export function finiteSelectionOverlayRect(sel: SelectionRectResult | null): sel is SelectionRectResult {
  if (!sel) return false
  return (
    Number.isFinite(sel.width) &&
    Number.isFinite(sel.height) &&
    Number.isFinite(sel.center.x) &&
    Number.isFinite(sel.center.y) &&
    Number.isFinite(sel.transform.a) &&
    Number.isFinite(sel.transform.b) &&
    Number.isFinite(sel.transform.c) &&
    Number.isFinite(sel.transform.d)
  )
}
