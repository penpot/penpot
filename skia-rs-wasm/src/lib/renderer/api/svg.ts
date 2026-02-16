/**
 * SVG attributes and raw content
 */

import type { WasmModule } from '../wasm-types'
import { translateFillRule, translateStrokeLinecap, translateStrokeLinejoin } from './serializers'
import { checkContext } from './context'

/**
 * Set shape SVG attributes
 */
export function setShapeSvgAttrs(module: WasmModule, attrs: Record<string, any>): void {
  checkContext(module)
  const style = attrs.style || {}
  const allowedKeys = ['fill', 'fillRule', 'strokeLinecap', 'strokeLinejoin']
  const filtered: Record<string, any> = {}

  for (const key of allowedKeys) {
    if (attrs[key] !== undefined) {
      filtered[key] = attrs[key]
    }
    if (style[key] !== undefined) {
      filtered[key] = style[key]
    }
  }

  const fillRule = translateFillRule(filtered.fillRule)
  const strokeLinecap = translateStrokeLinecap(filtered.strokeLinecap)
  const strokeLinejoin = translateStrokeLinejoin(filtered.strokeLinejoin)
  const fillNone = filtered.fill === 'none' ? 1 : 0

  module._set_shape_svg_attrs(fillRule, strokeLinecap, strokeLinejoin, fillNone)
}

/**
 * Set shape SVG raw content
 */
export function setShapeSvgRawContent(module: WasmModule, content: string): void {
  checkContext(module)
  const size = content.length + 1
  const offset = module._malloc(size)
  module.stringToUTF8(content, offset, size)
  module._set_shape_svg_raw_content()
  module._free(offset)
}

