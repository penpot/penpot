/**
 * SVG utility functions for rendering
 */

import type { SvgContent } from '../types'
import type { Matrix } from 'penpot-exporter/lib'
import { isSvgContentTree } from '../verification'

/**
 * SVG tag names (from ClojureScript common/src/app/common/svg.cljc)
 */
const SVG_TAGS = new Set([
  'a', 'altGlyph', 'altGlyphDef', 'altGlyphItem', 'animate', 'animateColor',
  'animateMotion', 'animateTransform', 'circle', 'clipPath', 'color-profile',
  'cursor', 'defs', 'desc', 'ellipse', 'feBlend', 'feColorMatrix',
  'feComponentTransfer', 'feComposite', 'feConvolveMatrix', 'feDiffuseLighting',
  'feDisplacementMap', 'feDistantLight', 'feFlood', 'feFuncA', 'feFuncB',
  'feFuncG', 'feFuncR', 'feGaussianBlur', 'feImage', 'feMerge', 'feMergeNode',
  'feMorphology', 'feOffset', 'fePointLight', 'feSpecularLighting', 'feSpotLight',
  'feTile', 'feTurbulence', 'filter', 'font', 'font-face', 'font-face-format',
  'font-face-name', 'font-face-src', 'font-face-uri', 'foreignObject', 'g',
  'glyph', 'glyphRef', 'hkern', 'image', 'line', 'linearGradient', 'marker',
  'mask', 'metadata', 'missing-glyph', 'mpath', 'path', 'pattern', 'polygon',
  'polyline', 'radialGradient', 'rect', 'set', 'stop', 'style', 'svg', 'switch',
  'symbol', 'text', 'textPath', 'title', 'tref', 'tspan', 'use', 'view', 'vkern'
])

/**
 * SVG tags safe to wrap in <g> elements
 */
const SVG_GROUP_SAFE_TAGS = new Set([
  'animate', 'animateColor', 'animateMotion', 'animateTransform', 'set',
  'desc', 'metadata', 'title', 'circle', 'ellipse', 'line', 'path', 'polygon',
  'polyline', 'rect', 'defs', 'g', 'svg', 'symbol', 'use', 'linearGradient',
  'radialGradient', 'a', 'altGlyphDef', 'clipPath', 'color-profile', 'cursor',
  'filter', 'font', 'font-face', 'foreignObject', 'image', 'marker', 'mask',
  'pattern', 'style', 'switch', 'text', 'view'
])

/**
 * Graphic SVG elements that support transforms
 */
const GRAPHIC_ELEMENTS = new Set([
  'svg', 'circle', 'ellipse', 'image', 'line', 'path', 'polygon', 'polyline',
  'rect', 'symbol', 'text', 'textPath', 'use'
])

/**
 * Generate ID mapping for nested SVGs
 * Recursively visits SVG content tree and generates new IDs for elements with IDs
 */
export function generateIdMapping(content: SvgContent): Map<string, string> {
  const mapping = new Map<string, string>()

  function visitNode(node: SvgContent): void {
    if (isSvgContentTree(node)) {
      const elementId = node.attrs?.id
      if (elementId && typeof elementId === 'string') {
        // Generate new UUID-like ID
        const newId = generateUUID()
        mapping.set(elementId, newId)
      }

      // Recursively visit children
      if (node.content) {
        for (const child of node.content) {
          visitNode(child)
        }
      }
    }
  }

  visitNode(content)
  return mapping
}

/**
 * Generate a simple UUID-like string
 */
function generateUUID(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/**
 * Format transform matrix to CSS matrix() string
 */
export function formatMatrix(matrix: Matrix, precision: number = 2): string {
  const { a, b, c, d, e, f } = matrix
  return `matrix(${toFixed(a, precision)}, ${toFixed(b, precision)}, ${toFixed(c, precision)}, ${toFixed(d, precision)}, ${toFixed(e, precision)}, ${toFixed(f, precision)})`
}

/**
 * Convert transform to CSS transform string
 * Handles shape transforms (including flip-x, flip-y)
 */
export function formatTransform(
  shape: { transform?: Matrix; flipX?: boolean; flipY?: boolean },
  params?: { noFlip?: boolean }
): string {
  const { transform, flipX, flipY } = shape
  const noFlip = params?.noFlip

  if (!transform && !(noFlip && flipX) && !(noFlip && flipY)) {
    return ''
  }

  // For now, just format the transform matrix
  // Full implementation would handle flip-x/flip-y and transform-matrix calculation
  if (transform) {
    return formatMatrix(transform)
  }

  return ''
}

/**
 * Check if a tag is a valid SVG tag
 */
export function isSvgTag(tag: string): boolean {
  return SVG_TAGS.has(tag)
}

/**
 * Check if a tag is safe to wrap in <g> element
 */
export function isSvgGroupSafeTag(tag: string): boolean {
  return SVG_GROUP_SAFE_TAGS.has(tag)
}

/**
 * Get set of graphic SVG elements
 */
export function getGraphicElements(): Set<string> {
  return new Set(GRAPHIC_ELEMENTS)
}

/**
 * Check if a tag is a graphic element
 */
export function isGraphicElement(tag: string): boolean {
  return GRAPHIC_ELEMENTS.has(tag)
}

/**
 * Calculate SVG transform matrix for a shape
 * Handles viewbox scaling if present
 * Simplified version - full implementation would handle all viewbox cases
 */
export function svgTransformMatrix(shape: {
  svgViewbox?: { x: number; y: number; width: number; height: number }
  selrect?: { x: number; y: number; width: number; height: number }
  transform?: Matrix
  type?: string
}): string {
  if (shape.svgViewbox && shape.selrect) {
    const viewbox = shape.svgViewbox
    const selrect = shape.selrect

    const scaleX = selrect.width / viewbox.width
    const scaleY = selrect.height / viewbox.height

    // Calculate transform matrix
    // Simplified - full implementation would use proper matrix math
    const tx = selrect.x - scaleX * viewbox.x
    const ty = selrect.y - scaleY * viewbox.y

    // For paths and groups, include shape transform
    if (shape.type === 'path' || shape.type === 'group') {
      if (shape.transform) {
        return formatMatrix(shape.transform)
      }
    }

    // Return transform string for viewbox scaling
    return `translate(${tx}, ${ty}) scale(${scaleX}, ${scaleY})`
  }

  // No viewbox, return identity or shape transform
  if (shape.transform) {
    return formatMatrix(shape.transform)
  }

  return ''
}

/**
 * Helper to format number with fixed precision
 */
function toFixed(value: number, precision: number): string {
  return value.toFixed(precision)
}

