/**
 * Overlay rects at pointer rate; React reads via `useSignalCoalesced`.
 * `querySelectionRect` is the stateless WASM query used after selection/renderer changes and commits.
 */

import { signal } from '@preact/signals-core'
import type { Selrect } from 'penpot-exporter/types'
import type { Renderer } from '../renderer'
import type { SelectionRectResult } from '../types'

export const wasmSelectionRect = signal<SelectionRectResult | null>(null)
export const selectionRect = signal<Selrect | null>(null)
export const shapeDrawPreview = signal<Selrect | null>(null)

/** Synced from React: showHandles && showCornerHandles. Drives imperative corner-square `effect()`. */
export const selectionCornerHandlesVisible = signal(false)

function isFiniteSelectionRect(value: SelectionRectResult | null): value is SelectionRectResult {
  if (!value) return false
  return (
    Number.isFinite(value.width) &&
    Number.isFinite(value.height) &&
    Number.isFinite(value.center.x) &&
    Number.isFinite(value.center.y) &&
    Number.isFinite(value.transform.a) &&
    Number.isFinite(value.transform.b) &&
    Number.isFinite(value.transform.c) &&
    Number.isFinite(value.transform.d) &&
    Number.isFinite(value.transform.e) &&
    Number.isFinite(value.transform.f)
  )
}

export function querySelectionRect(renderer: Renderer, ids: Iterable<string>): SelectionRectResult | null {
  const result = renderer.getSelectionRect(Array.from(ids))
  return isFiniteSelectionRect(result) ? result : null
}
