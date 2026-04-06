/**
 * Glass effect operations
 */

import type { WasmModule } from '../wasm-types'
import type { Glass } from 'penpot-exporter/types'
import { checkContext } from './context'

/**
 * Set shape glass (liquid glass) effect
 */
export function setShapeGlass(module: WasmModule, glass: Glass | null | undefined): void {
  checkContext()
  if (glass) {
    module._set_shape_glass(
      glass.radius ?? 10,
      glass.refraction ?? 1.5,
      glass.depth ?? 10,
      glass.dispersion ?? 0.03,
      glass.lightIntensity ?? 0.5,
      (glass.lightAngle ?? 45) * (Math.PI / 180),
      glass.hidden ? 1 : 0,
    )
  } else {
    module._clear_shape_glass()
  }
}
