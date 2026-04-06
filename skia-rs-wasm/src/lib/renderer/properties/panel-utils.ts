import type { Blur, Fill, Glass, PenpotNode, Shadow, Stroke } from 'penpot-exporter/types'

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

export const DEFAULT_SHADOW: Shadow = {
  id: null,
  style: 'drop-shadow',
  offsetX: 4,
  offsetY: 4,
  blur: 4,
  spread: 0,
  hidden: false,
  color: { color: '#000000', opacity: 0.2 },
}

export const DEFAULT_BLUR: Blur = {
  type: 'layer-blur',
  value: 4,
  hidden: false,
}

export const DEFAULT_GLASS: Glass = {
  radius: 10,
  refraction: 1.5,
  depth: 10,
  dispersion: 0.03,
  lightIntensity: 0.5,
  lightAngle: 45,
  hidden: false,
}

/** Max stacked effects (shadows + blur + glass) per shape. */
export const MAX_EFFECTS = 8

export type EffectKind = 'drop-shadow' | 'inner-shadow' | 'layer-blur' | 'glass'

export type EffectItem =
  | { kind: 'drop-shadow'; shadow: Shadow }
  | { kind: 'inner-shadow'; shadow: Shadow }
  | { kind: 'layer-blur'; blur: Blur }
  | { kind: 'glass'; glass: Glass }

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
