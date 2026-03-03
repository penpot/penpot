/**
 * Shadow operations
 */

import type { WasmModule } from '../wasm-types'
import type { Shadow } from 'penpot-exporter/lib'
import { colorToU32ARGB } from '../types'
import { translateShadowStyle } from './serializers'
import { checkContext } from './context'

/**
 * Set shape shadows
 */
export function setShapeShadows(module: WasmModule, shadows: Shadow[]): void {
  checkContext()
  module._clear_shape_shadows()

  for (const shadow of shadows) {
    const rgba = colorToU32ARGB({
      color: shadow.color?.color ?? '',
      opacity: shadow.color?.opacity ?? 1,
    })
    module._add_shape_shadow(
      rgba,
      shadow.blur,
      shadow.spread,
      shadow.offsetX,
      shadow.offsetY,
      translateShadowStyle(shadow.style),
      shadow.hidden ? 1 : 0
    )
  }
}

