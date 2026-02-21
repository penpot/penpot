/**
 * Fill operations and image loading
 */

import type { WasmModule } from '../wasm-types'
import type { Fill, Gradient, ImageColor } from '@penpot-exporter/types'
import type { PendingImageCallback } from '@skia-rs-wasm/common'
import { uuidToU32Tuple, colorToU32ARGB } from '@skia-rs-wasm/common'
import {
  allocBytes,
  freeBytes,
  writeUUIDToDataView,
} from '../utils'
import { checkContext } from './context'
import {
  FILL_U8_SIZE,
  GRADIENT_STOP_U8_SIZE,
  MAX_GRADIENT_STOPS,
  isColorFill,
  isLinearGradient,
  isRadialGradient,
  isImageFill,
} from './constants'
import { getWebGLContext } from './webgl-helpers'

/**
 * Creates a WebGL texture from an ImageBitmap
 */
function createWebGLTextureFromImage(gl: WebGL2RenderingContext, image: ImageBitmap): WebGLTexture {
  const texture = gl.createTexture()
  if (!texture) {
    throw new Error('Failed to create WebGL texture')
  }
  
  gl.bindTexture(gl.TEXTURE_2D, texture)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image)
  gl.bindTexture(gl.TEXTURE_2D, null)
  
  return texture
}

/**
 * Gets texture ID for GL object system
 */
function getTextureIdForGLObject(module: WasmModule, texture: WebGLTexture): number {
  const glObj = module.GL
  const textures = glObj.textures
  const newId = glObj.getNewId(textures)
  textures[newId] = texture
  return newId
}

/**
 * Retrieves an image from a URL and creates an ImageBitmap
 */
async function retrieveImage(url: string): Promise<ImageBitmap> {
  const response = await fetch(url)
  const blob = await response.blob()
  return await createImageBitmap(blob)
}

/**
 * Fetches an image and creates a WebGL texture, storing it in WASM
 * Returns a pending callback object for async image loading
 */
export function fetchImage(
  module: WasmModule,
  shapeId: string,
  imageId: string,
  thumbnail: boolean,
  resolveImageUrl?: (imageId: string, thumbnail: boolean) => string
): PendingImageCallback {
  // Resolve image URL - use provided callback or construct default URL
  const url = resolveImageUrl
    ? resolveImageUrl(imageId, thumbnail)
    : `/assets/by-file-media-id/${imageId}${thumbnail ? '/thumbnail' : ''}`
  
  return {
    key: url,
    thumbnail,
    callback: async (): Promise<boolean> => {
      try {
        const img = await retrieveImage(url)
        const gl = getWebGLContext(module)
        
        if (!gl) {
          console.warn('WebGL context not available for image loading')
          return false
        }
        
        const texture = createWebGLTextureFromImage(gl, img)
        const textureId = getTextureIdForGLObject(module, texture)
        const width = img.width
        const height = img.height
        
        // Header: 32 bytes (2 UUIDs) + 4 bytes (thumbnail) + 4 bytes (texture ID) + 8 bytes (dimensions)
        const totalBytes = 48
        const offset = allocBytes(module, totalBytes)
        const dataView = new DataView(module.HEAPU8.buffer, module.HEAPU8.byteOffset)
        
        // 1. Set shape id (offset + 0 to offset + 15)
        writeUUIDToDataView(dataView, offset, shapeId)
        
        // 2. Set image id (offset + 16 to offset + 31)
        writeUUIDToDataView(dataView, offset + 16, imageId)
        
        // 3. Set thumbnail flag as u32 (offset + 32)
        dataView.setUint32(offset + 32, thumbnail ? 1 : 0, true)
        
        // 4. Set texture ID (offset + 36)
        dataView.setUint32(offset + 36, textureId, true)
        
        // 5. Set width (offset + 40)
        dataView.setUint32(offset + 40, width, true)
        
        // 6. Set height (offset + 44)
        dataView.setUint32(offset + 44, height, true)
        
        module._store_image_from_texture()
        freeBytes(module)
        
        return true
      } catch (error) {
        console.error('Could not fetch image', {
          imageId,
          thumbnail,
          url,
          error,
        })
        return false
      }
    },
  }
}

/**
 * Writes a solid color fill to the heap (exporter: fillColor, fillOpacity)
 */
export function writeSolidFill(
  offset: number,
  dataView: DataView,
  color: string,
  opacity: number
): void {
  // Type byte: 0x00 for solid fill
  dataView.setUint8(offset, 0x00)
  // Padding (3 bytes) - already zeroed
  // ARGB color at offset + 4
  const argb = colorToU32ARGB({ color, opacity })
  dataView.setUint32(offset + 4, argb, true)
}

/**
 * Writes a linear gradient fill to the heap (exporter Gradient: startX, startY, endX, endY, stops)
 */
export function writeLinearGradientFill(
  offset: number,
  dataView: DataView,
  gradient: Gradient,
  opacity: number
): void {
  // Type byte: 0x01 for linear gradient
  dataView.setUint8(offset, 0x01)
  // Padding (3 bytes) - already zeroed

  // Start coordinates (offset + 4)
  dataView.setFloat32(offset + 4, gradient.startX, true)
  dataView.setFloat32(offset + 8, gradient.startY, true)

  // End coordinates (offset + 12)
  dataView.setFloat32(offset + 12, gradient.endX, true)
  dataView.setFloat32(offset + 16, gradient.endY, true)

  // Alpha (offset + 20)
  const alpha = Math.floor(opacity * 0xff)
  dataView.setUint8(offset + 20, alpha)

  // Width (offset + 24) - unused for linear, set to 0
  dataView.setFloat32(offset + 24, 0, true)

  // Stop count (offset + 28)
  const stops = gradient.stops.slice(0, MAX_GRADIENT_STOPS)
  dataView.setUint8(offset + 28, stops.length)

  // Padding (3 bytes at offset + 29) - already zeroed

  // Write stops (offset + 32) - exporter stop: { color, opacity?, offset }
  let stopOffset = offset + 32
  for (const stop of stops) {
    const stopColor = colorToU32ARGB({
      color: stop.color,
      opacity: stop.opacity,
    })
    dataView.setUint32(stopOffset, stopColor, true)
    dataView.setFloat32(stopOffset + 4, stop.offset, true)
    stopOffset += GRADIENT_STOP_U8_SIZE
  }
}

/**
 * Writes a radial gradient fill to the heap (exporter Gradient: startX, startY = center, width = radius)
 */
export function writeRadialGradientFill(
  offset: number,
  dataView: DataView,
  gradient: Gradient,
  opacity: number
): void {
  // Type byte: 0x02 for radial gradient
  dataView.setUint8(offset, 0x02)
  // Padding (3 bytes) - already zeroed

  // Center (offset + 4)
  dataView.setFloat32(offset + 4, gradient.startX, true)
  dataView.setFloat32(offset + 8, gradient.startY, true)

  // End = center + radius (offset + 12)
  dataView.setFloat32(offset + 12, gradient.startX, true)
  dataView.setFloat32(offset + 16, gradient.startY + gradient.width, true)

  // Alpha (offset + 20)
  const alpha = Math.floor(opacity * 0xff)
  dataView.setUint8(offset + 20, alpha)

  // Width (offset + 24) - radius for radial
  dataView.setFloat32(offset + 24, gradient.width, true)

  // Stop count (offset + 28)
  const stops = gradient.stops.slice(0, MAX_GRADIENT_STOPS)
  dataView.setUint8(offset + 28, stops.length)

  // Padding (3 bytes at offset + 29) - already zeroed

  // Write stops (offset + 32)
  let stopOffset = offset + 32
  for (const stop of stops) {
    const stopColor = colorToU32ARGB({
      color: stop.color,
      opacity: stop.opacity,
    })
    dataView.setUint32(stopOffset, stopColor, true)
    dataView.setFloat32(stopOffset + 4, stop.offset, true)
    stopOffset += GRADIENT_STOP_U8_SIZE
  }
}

/**
 * Writes an image fill to the heap (exporter ImageColor: id?, width, height)
 */
export function writeImageFill(
  offset: number,
  dataView: DataView,
  imageColor: ImageColor,
  opacity: number
): void {
  // Type byte: 0x03 for image fill
  dataView.setUint8(offset, 0x03)
  // Padding (3 bytes) - already zeroed

  // UUID (offset + 4 to offset + 19)
  writeUUIDToDataView(dataView, offset + 4, imageColor.id ?? '00000000-0000-0000-0000-000000000000')

  // Alpha (offset + 20)
  const alpha = Math.floor(opacity * 0xff)
  dataView.setUint8(offset + 20, alpha)

  // Flags (offset + 21) - keep-aspect-ratio flag
  const keepAspectRatio = imageColor.keepAspectRatio === true ? 0x01 : 0x00
  dataView.setUint8(offset + 21, keepAspectRatio)

  // Padding (2 bytes at offset + 22) - already zeroed

  // Width (offset + 24)
  dataView.setUint32(offset + 24, imageColor.width, true)

  // Height (offset + 28)
  dataView.setUint32(offset + 28, imageColor.height, true)
}

/**
 * Set shape fills
 * Complete implementation supporting all fill types: solid colors, linear gradients, radial gradients, and images.
 */
export function setShapeFills(
  module: WasmModule,
  shapeId: string,
  fills: Fill[],
  thumbnail: boolean = false,
  resolveImageUrl?: (imageId: string, thumbnail: boolean) => string
): PendingImageCallback[] {
  checkContext(module)
  const pending: PendingImageCallback[] = []

  if (fills.length === 0) {
    module._clear_shape_fills()
    return pending
  }

  // Calculate allocation size: header (4 bytes) + fills (FILL_U8_SIZE each)
  const headerSize = 4
  const totalSize = headerSize + fills.length * FILL_U8_SIZE

  const offset = allocBytes(module, totalSize)
  const dataView = new DataView(module.HEAPU8.buffer, module.HEAPU8.byteOffset)

  // Write header: number of fills (u32)
  dataView.setUint32(offset, fills.length, true)

  // Write each fill (exporter: fillColor, fillOpacity, fillColorGradient, fillImage)
  for (let i = 0; i < fills.length; i++) {
    const fill = fills[i]
    const fillOffset = offset + headerSize + i * FILL_U8_SIZE
    const fillOpacity = fill.fillOpacity ?? 1

    if (isColorFill(fill)) {
      writeSolidFill(fillOffset, dataView, fill.fillColor, fillOpacity)
    } else if (isLinearGradient(fill)) {
      writeLinearGradientFill(fillOffset, dataView, fill.fillColorGradient, fillOpacity)
    } else if (isRadialGradient(fill)) {
      writeRadialGradientFill(fillOffset, dataView, fill.fillColorGradient, fillOpacity)
    } else if (isImageFill(fill)) {
      writeImageFill(fillOffset, dataView, fill.fillImage, fillOpacity)

      if (fill.fillImage?.id) {
        const imageId = fill.fillImage.id
        const [a, b, c, d] = uuidToU32Tuple(imageId)
        const cached = module._is_image_cached(a, b, c, d, thumbnail)
        if (cached === 0) {
          pending.push(fetchImage(module, shapeId, imageId, thumbnail, resolveImageUrl))
        }
      }
    }
  }

  // Send fills to WASM
  module._set_shape_fills()
  freeBytes(module)

  return pending
}

