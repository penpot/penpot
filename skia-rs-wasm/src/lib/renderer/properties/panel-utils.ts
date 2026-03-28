import type { Fill, PenpotNode } from 'penpot-exporter/types'

export const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

export const DEFAULT_FILL: Fill = { fillColor: '#3B82F6', fillOpacity: 1 }

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
