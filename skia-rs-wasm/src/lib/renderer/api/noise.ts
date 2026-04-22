/**
 * Noise effect transformer.
 *
 * Slots are written into shared WASM memory as:
 *   [u32 count] [u8 kind_0]…[u8 kind_{count-1}] [zero-padding to 4B] [u32 color_0]…
 *
 * `kind` is 0 for solid, 1 for prism. Colors are ARGB u32:
 *   - solid: standard packed ARGB from the user's hex + opacity.
 *   - prism: alpha byte = slot opacity; rgb bytes unused.
 */

import type { WasmModule } from '../wasm-types'
import type { Noise, NoiseSlot } from '../properties/panel-utils'
import { MAX_NOISE_SLOTS } from '../properties/panel-utils'
import { colorToU32ARGB } from '../types'
import { checkContext } from './context'
import { allocBytes, freeBytes } from '../utils'

function slotToU32(slot: NoiseSlot): number {
  if (slot.kind === 'prism') {
    const alpha = Math.round(Math.max(0, Math.min(1, slot.opacity)) * 255) & 0xff
    return (alpha << 24) >>> 0
  }
  return (
    colorToU32ARGB({
      color: slot.color,
      opacity: slot.opacity,
    }) >>> 0
  )
}

/**
 * Set the noise effect on the current shape. Clears when `noise` is null.
 */
export function setShapeNoise(module: WasmModule, noise: Noise | null | undefined): void {
  checkContext()
  if (!noise) {
    module._clear_shape_noise()
    return
  }

  const slotsIn: NoiseSlot[] = noise.slots && noise.slots.length > 0
    ? noise.slots
    : [{ kind: 'solid', color: '#000000', opacity: 1 }]
  const count = Math.min(slotsIn.length, MAX_NOISE_SLOTS)

  // Layout: [u32 count][u8 kind_i × count][pad to 4B][u32 color_i × count]
  const kindsSize = (count + 3) & ~3  // round up to 4-byte boundary
  const totalSize = 4 + kindsSize + count * 4
  const offset = allocBytes(module, totalSize)
  const heap = module.HEAPU8
  const dv = new DataView(heap.buffer, heap.byteOffset)

  dv.setUint32(offset, count, true)
  for (let i = 0; i < kindsSize; i++) {
    heap[offset + 4 + i] = 0
  }
  for (let i = 0; i < count; i++) {
    heap[offset + 4 + i] = slotsIn[i].kind === 'prism' ? 1 : 0
  }
  const colorsOffset = offset + 4 + kindsSize
  for (let i = 0; i < count; i++) {
    dv.setUint32(colorsOffset + i * 4, slotToU32(slotsIn[i]), true)
  }

  module._set_shape_noise(
    noise.noiseSize ?? 50,
    noise.density ?? 0.5,
    noise.softness ?? 0,
    noise.applyToFill ? 1 : 0,
    noise.hidden ? 1 : 0,
  )

  freeBytes(module)
}
