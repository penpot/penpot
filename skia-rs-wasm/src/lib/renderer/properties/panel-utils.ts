import type { Fill, PenpotNode, Stroke } from 'penpot-exporter/types'

export const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

export const DEFAULT_FILL: Fill = { fillColor: '#3B82F6', fillOpacity: 1 }

/** Max stacked fills per shape (matches Penpot `types.fills/MAX-FILLS`). */
export const MAX_FILLS = 8

export const DEFAULT_STROKE: Stroke = {
  strokeColor: '#000000',
  strokeOpacity: 1,
  strokeWidth: 1,
  strokeAlignment: 'center',
  strokeStyle: 'solid',
}

/** Max stacked strokes per shape. */
export const MAX_STROKES = 8

export function normalizeHex(input: string): string {
  let s = input.trim()
  if (!s.startsWith('#')) s = `#${s}`
  if (/^#[0-9A-Fa-f]{3}$/.test(s)) {
    const r = s[1]
    const g = s[2]
    const b = s[3]
    s = `#${r}${r}${g}${g}${b}${b}`
  }
  if (/^#[0-9A-Fa-f]{6}$/.test(s)) return s
  return '#FFFFFF'
}

export type RectLikeNode = PenpotNode & { x?: number; y?: number; width?: number; height?: number }
