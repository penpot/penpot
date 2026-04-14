/**
 * Glass effect operations
 *
 * All parameters use their physical/direct ranges.
 * Angles are passed in degrees and converted to radians here.
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
      glass.surfaceType ?? 1,                                   // squircle default
      glass.bezelWidth ?? 40,                                   // pixels
      glass.glassThickness ?? 1.2,                              // multiplier
      glass.refractiveIndex ?? 1.5,                             // physical index
      ((glass.specularAngle ?? -60) * Math.PI) / 180,           // degrees → radians
      glass.specularOpacity ?? 0.5,                             // 0–1
      glass.specularSaturation ?? 4,                            // 0=white, 9=vivid prismatic
      glass.chromaticAberration ?? 3.0,                         // pixels
      glass.splay ?? 1.0,                                       // 0–1
      ((glass.tiltAngle ?? 0) * Math.PI) / 180,                 // degrees → radians
      glass.edgeBoost ?? 0,                                     // 0–5
      (glass.zoom ?? 100) / 100,                                  // percentage → multiplier
      glass.blur ?? 0,                                          // sigma 0–20
      glass.frost ?? 0,                                         // 0–1
      glass.hidden ? 1 : 0,
    )
  } else {
    module._clear_shape_glass()
  }
}
