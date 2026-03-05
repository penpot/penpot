/**
 * SVG attributes and raw content
 */

import type { WasmModule } from '../wasm-types'
import type { ShapeAttributes } from 'penpot-exporter/types'
import { translateFillRule, translateStrokeLinecap, translateStrokeLinejoin } from './serializers'
import { checkContext } from './context'

/** SVG attrs type aligned with penpot-exporter ShapeAttributes.svgAttrs */
type SvgAttrs = NonNullable<ShapeAttributes['svgAttrs']>

/**
 * Set shape SVG attributes
 */
export function setShapeSvgAttrs(module: WasmModule, attrs: SvgAttrs): void {
  checkContext()
  const style = (attrs.style ?? {}) as Record<string, unknown>
  const allowedKeys = ['fill', 'fillRule', 'strokeLinecap', 'strokeLinejoin']
  const filtered: Record<string, unknown> = {}

  for (const key of allowedKeys) {
    if (attrs[key] !== undefined) {
      filtered[key] = attrs[key]
    }
    if (style[key] !== undefined) {
      filtered[key] = style[key]
    }
  }

  const fillRule = translateFillRule(filtered.fillRule as string | undefined)
  const strokeLinecap = translateStrokeLinecap(filtered.strokeLinecap as string | undefined)
  const strokeLinejoin = translateStrokeLinejoin(filtered.strokeLinejoin as string | undefined)
  const fillNone = filtered.fill === 'none' ? 1 : 0

  module._set_shape_svg_attrs(fillRule, strokeLinecap, strokeLinejoin, fillNone)
}

/**
 * Set shape SVG raw content
 */
export function setShapeSvgRawContent(module: WasmModule, content: string): void {
  checkContext()
  const size = content.length + 1
  const offset = module._malloc(size)
  module.stringToUTF8(content, offset, size)
  module._set_shape_svg_raw_content()
  module._free(offset)
}

