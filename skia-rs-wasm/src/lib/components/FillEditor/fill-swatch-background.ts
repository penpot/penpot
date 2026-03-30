import type { Fill } from 'penpot-exporter/types'
import { isColorFill, isImageFill, MAX_GRADIENT_STOPS } from '../../renderer/api/constants'

const defaultStops = [
  { offset: 0, color: '#3B82F6', opacity: 1 },
  { offset: 1, color: '#1E40AF', opacity: 1 },
]

function fillToStops(fill: Fill): { offset: number; color: string; opacity?: number }[] {
  const g = fill.fillColorGradient
  if (!g?.stops?.length) return [...defaultStops]
  return g.stops.slice(0, MAX_GRADIENT_STOPS).map((s) => ({
    offset: s.offset,
    color: s.color,
    opacity: s.opacity ?? 1,
  }))
}

function stopsToGradientCss(stops: { offset: number; color: string; opacity?: number }[]): string {
  const sorted = [...stops].sort((a, b) => a.offset - b.offset)
  const parts = sorted.map((s) => {
    const o = Math.round(s.offset * 100)
    const opacity = s.opacity ?? 1
    const hex = s.color
    const r = parseInt(hex.slice(1, 3), 16)
    const g = parseInt(hex.slice(3, 5), 16)
    const b = parseInt(hex.slice(5, 7), 16)
    return `rgba(${r},${g},${b},${opacity}) ${o}%`
  })
  return `linear-gradient(90deg, ${parts.join(', ')})`
}

/** Preview background for a compact fill swatch (solid, gradient strip, or image pattern). */
export function fillSwatchBackground(fill: Fill): string {
  if (isImageFill(fill)) {
    return 'repeating-linear-gradient(45deg, #d4d4d4 0 4px, #f5f5f5 4px 8px)'
  }
  if (isColorFill(fill)) return fill.fillColor ?? '#3B82F6'
  const g = fill.fillColorGradient
  if (!g?.stops?.length) return '#888888'
  return stopsToGradientCss(fillToStops(fill))
}
