/**
 * Type guards and verification helpers for the renderer.
 */

import type { Fill, Gradient, ImageColor } from 'penpot-exporter/lib'
import type { SvgContent } from './types'

export function isSvgContentTree(
  content: SvgContent
): content is { tag: string; attrs?: Record<string, unknown>; content?: SvgContent[] } {
  return typeof content === 'object' && content !== null && 'tag' in content
}

export function isSvgContentString(content: SvgContent): content is string {
  return typeof content === 'string'
}

export function isSvgContent(
  content: Record<string, unknown> | SvgContent | undefined
): content is SvgContent {
  if (content === undefined) {
    return false
  }
  return (
    typeof content === 'string' ||
    (typeof content === 'object' && content !== null && 'tag' in content)
  )
}

export function isColorFill(fill: Fill): fill is Fill & { fillColor: string } {
  return (
    fill.fillColor != null &&
    fill.fillColorGradient == null &&
    fill.fillImage == null
  )
}

export function isLinearGradient(
  fill: Fill
): fill is Fill & { fillColorGradient: Gradient & { type: 'linear' } } {
  return fill.fillColorGradient != null && fill.fillColorGradient.type === 'linear'
}

export function isRadialGradient(
  fill: Fill
): fill is Fill & { fillColorGradient: Gradient & { type: 'radial' } } {
  return fill.fillColorGradient != null && fill.fillColorGradient.type === 'radial'
}

export function isImageFill(
  fill: Fill
): fill is Fill & { fillImage: ImageColor } {
  return fill.fillImage != null
}
