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

export const DEFAULT_BACKGROUND_BLUR: Blur = {
  type: 'background-blur',
  value: 4,
  hidden: false,
}

export const DEFAULT_GLASS: Glass = {
  surfaceType: 1,           // squircle
  bezelWidth: 40,           // pixels
  glassThickness: 1.2,      // multiplier
  refractiveIndex: 1.5,     // physical index
  specularAngle: -60,       // degrees
  specularOpacity: 0.5,     // 0–1
  specularSaturation: 4,    // 0=white, 9=vivid prismatic
  chromaticAberration: 3,   // pixels
  splay: 1.0,               // dome
  tiltAngle: 0,             // degrees
  edgeBoost: 0,             // 0–5
  zoom: 100,                // percentage (100% = no zoom)
  blur: 0,                  // sigma
  frost: 0,                 // 0–1
  hidden: false,
}

/**
 * Noise effect — one unified list of "slots" that can be either a solid color
 * or a Prism (iridescent rainbow sampled from the noise itself).
 *
 * Density semantics:
 *  - With exactly 1 slot: transparent has weight 1 and the slot has weight
 *    `density`. So density=100% → 50/50 slot/transparent; density=50% → ~33%
 *    slot, ~67% transparent; density=0% → fully transparent.
 *  - With 2+ slots: `density` is classic coverage — the colored region is
 *    `density` of the shape, split equally among the slots; the rest is
 *    transparent.
 */
export type NoiseSlot =
  | { kind: 'solid'; color: string; opacity: number }
  | { kind: 'prism'; opacity: number }

export interface Noise {
  id?: string
  /** 1..MAX_NOISE_SLOTS slots. */
  slots?: NoiseSlot[]
  noiseSize?: number
  density?: number
  /**
   * Edge softness in [0, 1]. 0 = hard crisp edges (default, original
   * behavior); 1 = maximum feather (pastel-looking soft falloff). The
   * shader does a symmetric smoothstep around the density threshold.
   */
  softness?: number
  /**
   * When true, the noise only renders where the shape's fill has coverage
   * (matches Figma: no fill → no noise). Default false = noise covers the
   * shape's bounds regardless of fill.
   */
  applyToFill?: boolean
  hidden?: boolean
}

/** Maximum number of noise slots the shader supports. */
export const MAX_NOISE_SLOTS = 4

export const DEFAULT_NOISE: Noise = {
  slots: [{ kind: 'solid', color: '#000000', opacity: 1 }],
  noiseSize: 50,
  density: 0.5,
  softness: 0,
  applyToFill: false,
  hidden: false,
}

export type Texture = {
  noiseSize: number
  radius: number
  clipToShape: boolean
  hidden: boolean
}

export const DEFAULT_TEXTURE: Texture = {
  noiseSize: 100,
  radius: 0,
  clipToShape: true,
  hidden: false,
}

/** Max stacked effects (shadows + blur + glass + noise + texture) per shape. */
export const MAX_EFFECTS = 8

export type EffectKind =
  | 'drop-shadow'
  | 'inner-shadow'
  | 'layer-blur'
  | 'background-blur'
  | 'glass'
  | 'noise'
  | 'texture'

export type EffectItem =
  | { kind: 'drop-shadow'; shadow: Shadow }
  | { kind: 'inner-shadow'; shadow: Shadow }
  | { kind: 'layer-blur'; blur: Blur }
  | { kind: 'background-blur'; blur: Blur }
  | { kind: 'glass'; glass: Glass }
  | { kind: 'noise'; noise: Noise }
  | { kind: 'texture'; texture: Texture }

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
