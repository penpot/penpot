/**
 * Fill modifier operations for live-preview of fill changes during drag.
 * Mirrors the geometry modifier pattern (api/modifiers.ts) but for fills.
 */

import type { WasmModule } from '../wasm-types'
import type { Fill } from 'penpot-exporter/types'
import { allocBytes, freeBytes, writeUUIDToDataView } from '../utils'
import { checkContext } from './context'
import { requestRender } from './rendering'
import {
  FILL_U8_SIZE,
  isColorFill,
  isLinearGradient,
  isRadialGradient,
  isAngularGradient,
  isImageFill,
} from './constants'
import {
  writeSolidFill,
  writeLinearGradientFill,
  writeRadialGradientFill,
  writeAngularGradientFill,
  writeImageFill,
} from './fills'

// 16 bytes UUID + 4 bytes fill_count
const FILL_MODIFIER_HEADER = 20

export function setShapeFillModifier(
  module: WasmModule,
  shapeId: string,
  fills: Fill[],
  skipRender = false,
): void {
  checkContext()
  const totalSize = FILL_MODIFIER_HEADER + fills.length * FILL_U8_SIZE
  const offset = allocBytes(module, totalSize)
  const dataView = new DataView(module.HEAPU8.buffer, module.HEAPU8.byteOffset)

  writeUUIDToDataView(dataView, offset, shapeId)
  dataView.setUint32(offset + 16, fills.length, true)

  for (let i = 0; i < fills.length; i++) {
    const fill = fills[i]
    const fo = offset + FILL_MODIFIER_HEADER + i * FILL_U8_SIZE
    const opacity = fill.fillOpacity ?? 1
    if (isColorFill(fill)) writeSolidFill(fo, dataView, fill.fillColor, opacity)
    else if (isLinearGradient(fill)) writeLinearGradientFill(fo, dataView, fill.fillColorGradient, opacity)
    else if (isRadialGradient(fill)) writeRadialGradientFill(fo, dataView, fill.fillColorGradient, opacity)
    else if (isAngularGradient(fill)) writeAngularGradientFill(fo, dataView, fill.fillColorGradient, opacity)
    else if (isImageFill(fill)) writeImageFill(fo, dataView, fill.fillImage, fill.fillOpacity ?? 1)
  }

  module._set_fill_modifier()
  freeBytes(module)
  if (!skipRender) requestRender(module, 'set-fill-modifier')
}

export function cleanFillModifiers(module: WasmModule): void {
  checkContext()
  module._clean_fill_modifiers()
}
