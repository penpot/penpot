/**
 * Stroke operations
 */

import type { WasmModule } from '../wasm-types'
import type { Stroke } from 'penpot-exporter'
import type { PendingImageCallback } from '@skia-rs-wasm/common'
import { uuidToU32Tuple } from '@skia-rs-wasm/common'
import { allocBytes, freeBytes } from '../utils'
import {
  translateStrokeStyle,
  translateStrokeCap,
} from './serializers'
import { checkContext } from './context'
import { FILL_U8_SIZE } from './constants'
import {
  writeSolidFill,
  writeLinearGradientFill,
  writeRadialGradientFill,
  writeImageFill,
  fetchImage,
} from './fills'

/**
 * Set shape strokes
 */
export function setShapeStrokes(
  module: WasmModule,
  shapeId: string,
  strokes: Stroke[],
  thumbnail: boolean = false,
  resolveImageUrl?: (imageId: string, thumbnail: boolean) => string
): PendingImageCallback[] {
  checkContext(module)
  const pending: PendingImageCallback[] = []

  module._clear_shape_strokes()

  for (const stroke of strokes) {
    const opacity = stroke.strokeOpacity ?? 1.0
    const color = stroke.strokeColor
    const gradient = stroke.strokeColorGradient
    const image = stroke.strokeImage
    const width = stroke.strokeWidth ?? 0
    const align = stroke.strokeAlignment ?? 'center'
    const style = translateStrokeStyle(stroke.strokeStyle)
    const capStart = translateStrokeCap(stroke.strokeCapStart)
    const capEnd = translateStrokeCap(stroke.strokeCapEnd)

    // Add stroke based on alignment
    switch (align) {
      case 'inner':
        module._add_shape_inner_stroke(width, style, capStart, capEnd)
        break
      case 'outer':
        module._add_shape_outer_stroke(width, style, capStart, capEnd)
        break
      default:
        module._add_shape_center_stroke(width, style, capStart, capEnd)
    }

    // Write fill data
    const fillOffset = allocBytes(module, FILL_U8_SIZE)
    const dataView = new DataView(module.HEAPU8.buffer, module.HEAPU8.byteOffset)

    if (gradient) {
      if (gradient.type === 'linear') {
        writeLinearGradientFill(fillOffset, dataView, gradient, opacity)
      } else if (gradient.type === 'radial') {
        writeRadialGradientFill(fillOffset, dataView, gradient, opacity)
      }
      module._add_shape_stroke_fill()
    } else if (image) {
      writeImageFill(fillOffset, dataView, image, opacity)
      module._add_shape_stroke_fill()

      if (image.id) {
        const imageId = image.id
        const [a, b, c, d] = uuidToU32Tuple(imageId)
        const cached = module._is_image_cached(a, b, c, d, thumbnail)
        if (cached === 0) {
          pending.push(fetchImage(module, shapeId, imageId, thumbnail, resolveImageUrl))
        }
      }
    } else if (color) {
      writeSolidFill(fillOffset, dataView, color, opacity)
      module._add_shape_stroke_fill()
    }

    freeBytes(module)
  }

  return pending
}

