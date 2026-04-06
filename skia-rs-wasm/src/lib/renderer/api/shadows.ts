/**
 * Shadow operations
 */

import type { WasmModule } from '../wasm-types'
import type { Shadow } from 'penpot-exporter/types'
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
    // Gradient colors are not supported by the shadow WASM API.
    // Use the first gradient stop color as a solid fallback.
    let colorHex: string
    let colorOpacity: number
    if (shadow.color?.gradient?.stops?.length) {
      const stop = shadow.color.gradient.stops[0]
      colorHex = stop.color ?? '#000000'
      colorOpacity = stop.opacity ?? 1
    } else {
      colorHex = shadow.color?.color ?? '#000000'
      colorOpacity = shadow.color?.opacity ?? 1
    }
    const rgba = colorToU32ARGB({ color: colorHex, opacity: colorOpacity })
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

