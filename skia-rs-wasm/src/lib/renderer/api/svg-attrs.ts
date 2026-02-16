/**
 * SVG attributes utilities
 * Handles fill props, transforms, and other SVG attributes
 */

import type { PenpotNode } from '../types'
import { isColorFill, isLinearGradient, isRadialGradient, isImageFill } from './constants'

/**
 * Add fill props to an attributes object
 * Based on the ClojureScript implementation in frontend/src/app/main/ui/shapes/attrs.cljs
 */
export function addFillProps(
  props: Record<string, any>,
  shape: PenpotNode,
  renderId: string,
  position: number = 0
): Record<string, any> {
  const result = { ...props }
  const shapeFills = shape.fills || []
  const svgAttrs = shape.svgAttrs ?? {}
  const shapeType = shape.type

  // Extract SVG styles if present (from style object in svg-attrs)
  const svgStyles = svgAttrs.style || {}
  const style = { ...(props.style || {}), ...svgStyles }

  // Determine if URL fill is needed (multiple fills, gradients, or images)
  const hasImageFill = shapeFills.some((fill) => isImageFill(fill))
  const hasGradientFill = shapeFills.some(
    (fill) => isLinearGradient(fill) || isRadialGradient(fill)
  )
  const urlFillNeeded =
    hasImageFill ||
    hasGradientFill ||
    shapeFills.length > 1 ||
    shape.type === 'image'

  // Handle special case: SVG-raw or group with svgAttrs but no fills
  if (
    Object.keys(svgAttrs).length > 0 &&
    (shapeType === 'svg-raw' || shapeType === 'group') &&
    shapeFills.length === 0
  ) {
    const fill = svgAttrs.fill ?? '#000000'
    style.fill = fill
    result.style = style
    return { ...result, ...svgAttrs }
  }

  // Handle URL fill case (multiple fills, gradients, or images)
  if (urlFillNeeded) {
    // Remove fill from style, set it on props directly
    delete style.fill
    delete style.fillOpacity
    result.fill = `url(#fill-${position}-${renderId})`
    result.style = style
    return { ...result, ...svgAttrs }
  }

  // Handle SVG styles with fill (when present and fills exist)
  if (svgStyles && svgStyles.fill && shapeFills.length > 0) {
    if (svgStyles.fill) {
      style.fill = svgStyles.fill
    }
    if (svgStyles.fillOpacity !== undefined) {
      style.fillOpacity = svgStyles.fillOpacity
    }
    result.style = style
    return { ...result, ...svgAttrs }
  }

  // Handle SVG attrs with fill but no shape fills
  if (svgAttrs && Object.keys(svgAttrs).length > 0 && shapeFills.length === 0) {
    if (svgAttrs.fill) {
      style.fill = svgAttrs.fill
    }
    if (svgAttrs.fillOpacity !== undefined) {
      style.fillOpacity = svgAttrs.fillOpacity
    }
    result.style = style
    return { ...result, ...svgAttrs }
  }

  // Handle shape fills
  if (shapeFills.length > 0) {
    const firstFill = shapeFills[0]
    const svgFill = svgAttrs.fill
    const fillDefault = svgFill || 'none'

    // Apply fill based on type
    if (isImageFill(firstFill)) {
      // Image fill: use pattern URL
      result.fill = `url(#fill-image-${renderId})`
    } else if (isLinearGradient(firstFill) || isRadialGradient(firstFill)) {
      // Gradient fill: use gradient URL (index is empty for first fill)
      const index = ''
      result.fill = `url(#fill-color-gradient-${renderId}${index})`
    } else if (isColorFill(firstFill)) {
      result.fill = firstFill.fillColor ?? fillDefault
    } else {
      // Fallback
      result.fill = fillDefault
    }

    if (isColorFill(firstFill) || isImageFill(firstFill)) {
      if (firstFill.fillOpacity !== undefined && firstFill.fillOpacity !== 1) {
        result.fillOpacity = firstFill.fillOpacity
      }
    }

    // Special case: text shapes without explicit fill default to black
    if (
      shapeType === 'text' &&
      !isLinearGradient(firstFill) &&
      !isRadialGradient(firstFill) &&
      !isColorFill(firstFill)
    ) {
      result.fill = 'black'
    }
  } else {
    // No fills: handle special cases
    if (shapeType === 'path') {
      // Path shapes without fills should use fill="none"
      result.fill = 'none'
    } else if (shapeType === 'text') {
      // Text shapes without fills default to black
      result.fill = 'black'
    }
  }

  result.style = style
  return { ...result, ...svgAttrs }
}

