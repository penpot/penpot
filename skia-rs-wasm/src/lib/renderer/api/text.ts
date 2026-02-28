/**
 * Text operations
 */

import type { WasmModule } from '../wasm-types'
import type { PenpotNode, Fill, TextContent } from 'penpot-exporter'
import type { PendingImageCallback, ResolveFontUrlCallback, FontInfo, FontData } from '@skia-rs-wasm/common'
import { uuidToU32Tuple, uuidToU32 } from '@skia-rs-wasm/common'
import {
  freeBytes,
  offset8To32,
  allocBytes,
  writeUUIDToDataView,
} from '../utils'
import { checkContext } from './context'
import {
  POSITION_DATA_U32_SIZE,
  PARAGRAPH_ATTR_U8_SIZE,
  SPAN_ATTR_U8_SIZE,
  MAX_TEXT_FILLS,
  FILL_U8_SIZE,
  ZERO_UUID,
} from './constants'
import { isImageFill, isColorFill, isLinearGradient, isRadialGradient } from './constants'
import { moduleUseShape } from './shape'
import { setShapeVerticalAlign } from './shape'
import { fetchImage } from './fills'
import {
  translateTextAlign,
  translateTextDirection,
  translateFontStyle,
  translateTextDecoration,
  translateTextTransform,
} from './serializers'
import {
  writeSolidFill,
  writeLinearGradientFill,
  writeRadialGradientFill,
  writeImageFill,
} from './fills'

/**
 * Extracts fonts from text content (exporter: fontId, fontVariantId, fontWeight, fontStyle)
 */
function extractFontsFromContent(content: TextContent): FontInfo[] {
  const fonts: FontInfo[] = []
  const fontSet = new Set<string>()

  if (!content || !content.children) {
    return fonts
  }

  const paragraphSet = content.children[0]
  if (!paragraphSet || !paragraphSet.children) {
    return fonts
  }

  const paragraphs = paragraphSet.children

  for (const paragraph of paragraphs) {
    if (!paragraph.children) {
      continue
    }
    for (const span of paragraph.children) {
      const fontId = span.fontId ?? span.fontFamily ?? 'sourcesanspro'
      if (!fontSet.has(fontId)) {
        fontSet.add(fontId)
        fonts.push({
          fontId,
          fontVariantId: span.fontVariantId,
          fontWeight: span.fontWeight as number | undefined,
          fontStyle: span.fontStyle,
        })
      }
    }
  }

  return fonts
}

/**
 * Serializes font-weight to u32
 * Handles numeric and string values like "400", "bold"
 */
function serializeFontWeight(weight?: number | string): number {
  if (!weight) return 400
  if (typeof weight === 'number') return weight
  if (typeof weight === 'string') {
    const num = parseInt(weight, 10)
    if (!isNaN(num)) return num
    // Handle named weights
    const namedWeights: Record<string, number> = {
      normal: 400,
      bold: 700,
      lighter: 300,
      bolder: 700,
    }
    return namedWeights[weight.toLowerCase()] ?? 400
  }
  return 400
}

/**
 * Serializes font-size to f32
 */
function serializeFontSize(size?: number): number {
  return size ?? 14
}

/**
 * Serializes line-height to f32
 * Handles numeric and relative values
 */
function serializeLineHeight(height?: number | string, paragraphLineHeight?: number): number {
  if (!height) return paragraphLineHeight ?? 1.2
  if (typeof height === 'number') return height
  if (typeof height === 'string') {
    // Handle relative values like "1.5" or "150%"
    if (height.endsWith('%')) {
      return parseFloat(height) / 100
    }
    const num = parseFloat(height)
    if (!isNaN(num)) return num
  }
  return paragraphLineHeight ?? 1.2
}

/**
 * Serializes letter-spacing to f32
 */
function serializeLetterSpacing(spacing?: number): number {
  return spacing ?? 0.0
}

/**
 * Normalizes font-id to UUID format
 * Handles gfont-*, custom-*, builtin fonts
 */
function normalizeFontId(fontId?: string): string {
  if (!fontId) return ZERO_UUID
  // For now, return as-is - in a full implementation this would convert
  // gfont-* and custom-* prefixes to UUIDs
  // This is a simplified version - full implementation would query font database
  return fontId
}

/**
 * Normalizes font-variant-id to UUID or zero UUID
 */
function normalizeFontVariantId(variantId?: string): string {
  if (!variantId) return ZERO_UUID
  // Check if it's already a UUID format
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(variantId)) {
    return variantId
  }
  // For non-UUID variant IDs, return zero UUID
  // Full implementation would resolve variant ID from font database
  return ZERO_UUID
}

/**
 * Retrieves a font file from a URL and returns as ArrayBuffer
 */
async function retrieveFont(url: string): Promise<ArrayBuffer> {
  const response = await fetch(url)
  if (!response.ok) {
    throw new Error(`Failed to fetch font: ${response.statusText}`)
  }
  return await response.arrayBuffer()
}

/**
 * Fetches a font and stores it in WASM
 * Returns a pending callback object for async font loading
 */
function fetchFont(
  module: WasmModule,
  shapeId: string,
  fontData: FontData,
  fontUrl: string,
  emoji: boolean = false,
  fallback: boolean = false
): PendingImageCallback {
  return {
    key: fontUrl,
    thumbnail: false, // Fonts don't have thumbnails, but we use the same callback structure
    callback: async (): Promise<boolean> => {
      try {
        const fontBuffer = await retrieveFont(fontUrl)
        const size = fontBuffer.byteLength
        
        // Allocate WASM memory for font buffer
        const ptr = allocBytes(module, size)
        const heap = module.HEAPU8
        const mem = new Uint8Array(heap.buffer, heap.byteOffset + ptr, size)
        
        // Copy font bytes to WASM heap
        mem.set(new Uint8Array(fontBuffer))
        
        // Get UUID buffers
        const shapeIdBuffer = uuidToU32(shapeId)
        const fontIdBuffer = uuidToU32(fontData.wasmId)
        
        // Call WASM to store font
        module._store_font(
          shapeIdBuffer[0],
          shapeIdBuffer[1],
          shapeIdBuffer[2],
          shapeIdBuffer[3],
          fontIdBuffer[0],
          fontIdBuffer[1],
          fontIdBuffer[2],
          fontIdBuffer[3],
          fontData.weight,
          fontData.style,
          emoji ? 1 : 0,
          fallback ? 1 : 0
        )
        
        freeBytes(module)
        return true
      } catch (error) {
        console.error('Could not fetch font', {
          fontId: fontData.fontId,
          fontUrl,
          error,
        })
        return false
      }
    },
  }
}

/**
 * Stores a single font
 * Checks cache, fetches if needed
 */
function storeFont(
  module: WasmModule,
  shapeId: string,
  font: FontInfo,
  resolveFontUrl?: ResolveFontUrlCallback
): PendingImageCallback | null {
  const fontId = font.fontId
  if (!fontId) return null

  const fontVariantId = normalizeFontVariantId(font.fontVariantId)
  const fontWeight = serializeFontWeight(font.fontWeight)
  const fontStyleStr = font.fontStyle ?? 'normal'
  const fontStyle = translateFontStyle(fontStyleStr)
  const emoji = font.isEmoji ?? false
  const fallback = font.isFallback ?? false
  
  // Get WASM font ID (normalized UUID)
  const wasmId = normalizeFontId(fontId)
  const fontIdBuffer = uuidToU32(wasmId)
  
  // Check if font is already cached
  const cached = module._is_font_uploaded(
    fontIdBuffer[0],
    fontIdBuffer[1],
    fontIdBuffer[2],
    fontIdBuffer[3],
    fontWeight,
    fontStyle,
    emoji ? 1 : 0
  )
  
  if (cached !== 0) {
    // Font is already cached
    return null
  }
  
  // Resolve font URL
  const fontUrl = resolveFontUrl
    ? resolveFontUrl(fontId, font.fontVariantId, fontWeight, fontStyleStr)
    : `/fonts/${fontId}`
  
  // Create font data
  const fontData: FontData = {
    wasmId,
    fontId,
    fontVariantId,
    style: fontStyle,
    styleName: fontStyleStr,
    weight: fontWeight,
  }
  
  // Return pending callback
  return fetchFont(module, shapeId, fontData, fontUrl, emoji, fallback)
}

/**
 * Stores multiple fonts and returns pending callbacks
 */
function storeFonts(
  module: WasmModule,
  shapeId: string,
  fonts: FontInfo[],
  resolveFontUrl?: ResolveFontUrlCallback
): PendingImageCallback[] {
  const pending: PendingImageCallback[] = []
  for (const font of fonts) {
    const callback = storeFont(module, shapeId, font, resolveFontUrl)
    if (callback) {
      pending.push(callback)
    }
  }
  return pending
}

/**
 * Encodes text to UTF-8 buffer
 */
function encodeText(text: string): Uint8Array {
  const encoder = new TextEncoder()
  return encoder.encode(text)
}

/**
 * Writes span fills to heap
 * Returns next offset after fills
 */
function writeSpanFills(offset: number, dataView: DataView, fills: Fill[]): number {
  const fillsToWrite = fills.slice(0, MAX_TEXT_FILLS)
  let currentOffset = offset

  for (const fill of fillsToWrite) {
    const opacity = fill.fillOpacity ?? 1.0

    if (isColorFill(fill)) {
      writeSolidFill(currentOffset, dataView, fill.fillColor, opacity)
    } else if (isLinearGradient(fill)) {
      writeLinearGradientFill(currentOffset, dataView, fill.fillColorGradient!, opacity)
    } else if (isRadialGradient(fill)) {
      writeRadialGradientFill(currentOffset, dataView, fill.fillColorGradient!, opacity)
    } else if (isImageFill(fill)) {
      writeImageFill(currentOffset, dataView, fill.fillImage!, opacity)
    }

    currentOffset += FILL_U8_SIZE
  }
  
  // Pad remaining fills
  const paddingFills = MAX_TEXT_FILLS - fillsToWrite.length
  currentOffset += paddingFills * FILL_U8_SIZE
  
  return currentOffset
}

/**
 * Writes paragraph attributes to heap (12 bytes)
 * Returns next offset
 */
function writeParagraph(offset: number, dataView: DataView, paragraph: any): number {
  const textAlign = translateTextAlign(paragraph.textAlign)
  const textDirection = translateTextDirection(paragraph.textDirection)
  const textDecoration = translateTextDecoration(paragraph.textDecoration)
  const textTransform = translateTextTransform(paragraph.textTransform)
  const lineHeight = serializeLineHeight(paragraph.lineHeight)
  const letterSpacing = serializeLetterSpacing(paragraph.letterSpacing)
  
  dataView.setUint8(offset, textAlign)
  dataView.setUint8(offset + 1, textDirection)
  dataView.setUint8(offset + 2, textDecoration)
  dataView.setUint8(offset + 3, textTransform)
  dataView.setFloat32(offset + 4, lineHeight, true)
  dataView.setFloat32(offset + 8, letterSpacing, true)
  
  return offset + PARAGRAPH_ATTR_U8_SIZE
}

/**
 * Writes span attributes and fills to heap
 * Returns next offset
 */
function writeSpans(offset: number, dataView: DataView, spans: any[], paragraph: any): number {
  const paragraphFontSize = paragraph.fontSize ?? 14
  const paragraphFontWeight = serializeFontWeight(paragraph.fontWeight)
  const paragraphLineHeight = serializeLineHeight(paragraph.lineHeight)

  let currentOffset = offset

  for (const span of spans) {
    const fontStyle = translateFontStyle(span.fontStyle ?? paragraph.fontStyle ?? 'normal')
    const fontSize = serializeFontSize(span.fontSize ?? paragraphFontSize)
    const lineHeight = serializeLineHeight(span.lineHeight, paragraphLineHeight)
    const letterSpacing = serializeLetterSpacing(span.letterSpacing ?? paragraph.letterSpacing)
    const fontWeight = serializeFontWeight(span.fontWeight ?? paragraphFontWeight)
    const fontId = normalizeFontId(span.fontId ?? span.fontFamily ?? 'sourcesanspro')
    const fontFamily = hashString(span.fontFamily ?? span.fontId ?? 'sourcesanspro')
    const fontVariantId = normalizeFontVariantId(span.fontVariantId)

    const text = span.text ?? ''
    const textBuffer = encodeText(text)
    const textLength = textBuffer.length
    const fills = (span.fills ?? []).slice(0, MAX_TEXT_FILLS)

    const textDecoration = translateTextDecoration(
      span.textDecoration ?? paragraph.textDecoration ?? 'none'
    )
    const textTransform = translateTextTransform(
      span.textTransform ?? paragraph.textTransform ?? 'none'
    )
    const textDirection = translateTextDirection(
      span.textDirection ?? paragraph.textDirection ?? 'ltr'
    )
    
    // Write span attributes (64 bytes)
    dataView.setUint8(currentOffset, fontStyle)
    dataView.setUint8(currentOffset + 1, textDecoration)
    dataView.setUint8(currentOffset + 2, textTransform)
    dataView.setUint8(currentOffset + 3, textDirection)
    dataView.setFloat32(currentOffset + 4, fontSize, true)
    dataView.setFloat32(currentOffset + 8, lineHeight, true)
    dataView.setFloat32(currentOffset + 12, letterSpacing, true)
    dataView.setUint32(currentOffset + 16, fontWeight, true)
    
    // Write font-id UUID (16 bytes at offset + 20)
    writeUUIDToDataView(dataView, currentOffset + 20, fontId)
    
    // Write font-family hash (i32 at offset + 36)
    dataView.setInt32(currentOffset + 36, fontFamily, true)
    
    // Write font-variant-id UUID (16 bytes at offset + 40)
    writeUUIDToDataView(dataView, currentOffset + 40, fontVariantId)
    
    // Write text-length (i32 at offset + 56)
    dataView.setInt32(currentOffset + 56, textLength, true)
    
    // Write fill-count (i32 at offset + 60)
    dataView.setInt32(currentOffset + 60, fills.length, true)
    
    // Write fills (starting at offset + 64, but span attributes are only 64 bytes)
    // Actually, fills come after all span attributes
    currentOffset += SPAN_ATTR_U8_SIZE
    currentOffset = writeSpanFills(currentOffset, dataView, fills)
  }
  
  return currentOffset
}

/**
 * Simple string hash function (similar to ClojureScript hash)
 */
function hashString(str: string): number {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i)
    hash = ((hash << 5) - hash) + char
    hash = hash & hash // Convert to 32-bit integer
  }
  return hash
}

/**
 * Writes shape text content to WASM memory
 * Buffer format: [<num-spans: u32> <paragraph_attributes: 12 bytes> <spans_attributes: 64 bytes each + fills> <text: UTF-8>]
 */
function writeShapeText(module: WasmModule, content: TextContent): void {
  if (!content || !content.children) {
    return
  }
  
  // Get paragraph-set (first child)
  const paragraphSet = content.children[0]
  if (!paragraphSet || !paragraphSet.children) {
    return
  }
  
  const paragraphs = paragraphSet.children
  
  // Process first paragraph (simplified - full implementation would handle multiple paragraphs)
  if (paragraphs.length === 0) {
    return
  }
  
  const paragraph = paragraphs[0]
  const spans = paragraph.children || []
  
  if (spans.length === 0) {
    return
  }
  
  // Collect all text from spans
  const text = spans.map((span: any) => span.text || '').join('')
  const textBuffer = encodeText(text)
  const textSize = textBuffer.length
  
  // Calculate sizes
  const fillsSize = MAX_TEXT_FILLS * FILL_U8_SIZE
  const metadataSize = PARAGRAPH_ATTR_U8_SIZE + spans.length * (SPAN_ATTR_U8_SIZE + fillsSize)
  const totalSize = 4 + metadataSize + textSize // 4 bytes for num-spans
  
  // Allocate memory
  const offset = allocBytes(module, totalSize)
  const dataView = new DataView(module.HEAPU8.buffer, module.HEAPU8.byteOffset)
  const heapU8 = module.HEAPU8
  
  let currentOffset = offset
  
  // Write number of spans (u32)
  dataView.setUint32(currentOffset, spans.length, true)
  currentOffset += 4
  
  // Write paragraph attributes
  currentOffset = writeParagraph(currentOffset, dataView, paragraph)
  
  // Write spans
  currentOffset = writeSpans(currentOffset, dataView, spans, paragraph)
  
  // Write text buffer
  const textOffset = currentOffset
  for (let i = 0; i < textBuffer.length; i++) {
    heapU8[textOffset + i] = textBuffer[i]
  }
  
  // Call WASM to set text content
  module._set_shape_text_content()
  
  // Note: Memory will be freed by WASM after processing
}

/**
 * Set shape text content
 * Returns pending font loading operations
 */
export function setShapeTextContent(
  module: WasmModule,
  shapeId: string,
  content: TextContent,
  _resolveImageUrl?: (imageId: string, thumbnail: boolean) => string,
  resolveFontUrl?: ResolveFontUrlCallback
): PendingImageCallback[] {
  // Note: resolveImageUrl is kept for API compatibility but not used in this function
  // Image loading for text is handled separately via setShapeTextImages
  checkContext(module)
  module._clear_shape_text()

  if (content?.verticalAlign) {
    setShapeVerticalAlign(module, content.verticalAlign)
  }

  // Extract fonts from content
  const fonts = extractFontsFromContent(content)
  
  // Get fallback fonts (emoji, language-specific)
  const fallbackFonts = fontsFromTextContent(content, true)
  const allFonts = [...fonts, ...fallbackFonts]

  // Store fonts and get pending callbacks
  const pendingFontCallbacks = storeFonts(module, shapeId, allFonts, resolveFontUrl)
  
  // Serialize text tree structure to WASM
  // This should be called when processing fallback fonts (similar to ClojureScript line 793)
  // For now, we always serialize if we have content
  if (content && content.children && content.children.length > 0) {
    writeShapeText(module, content)
  }

  module._update_shape_text_layout()
  
  // Return pending font loading operations
  return pendingFontCallbacks
}

/**
 * Get text dimensions
 */
export function getTextDimensions(module: WasmModule, id?: string): {
  x: number
  y: number
  width: number
  height: number
  'max-width': number
} {
  checkContext(module)
  if (id) {
    moduleUseShape(module, id)
  }

  const offset = offset8To32(module._get_text_dimensions())
  const heapF32 = module.HEAPF32

  const width = heapF32[offset]
  const height = heapF32[offset + 1]
  const maxWidth = heapF32[offset + 2]
  const x = heapF32[offset + 3]
  const y = heapF32[offset + 4]

  freeBytes(module)

  return { x, y, width, height, 'max-width': maxWidth }
}

/**
 * Gets fill images from a text leaf/spans
 */
function getFillImages(leaf: any): Fill[] {
  if (!leaf || !leaf.fills) {
    return []
  }
  return leaf.fills.filter((fill: Fill) => isImageFill(fill))
}

/**
 * Processes a single fill image and returns pending callback if needed
 */
function processFillImage(
  module: WasmModule,
  shapeId: string,
  fill: Fill,
  thumbnail: boolean,
  resolveImageUrl?: (imageId: string, thumbnail: boolean) => string
): PendingImageCallback | null {
  if (!isImageFill(fill) || !fill.fillImage?.id) {
    return null
  }

  const imageId = fill.fillImage.id
  const [a, b, c, d] = uuidToU32Tuple(imageId)
  const cached = module._is_image_cached(a, b, c, d, thumbnail)
  
  if (cached === 0) {
    return fetchImage(module, shapeId, imageId, thumbnail, resolveImageUrl)
  }
  
  return null
}

/**
 * Set shape text images
 * Extracts image fills from text content and loads them
 */
export function setShapeTextImages(
  module: WasmModule,
  shapeId: string,
  content: TextContent,
  thumbnail: boolean = false,
  resolveImageUrl?: (imageId: string, thumbnail: boolean) => string
): PendingImageCallback[] {
  checkContext(module)
  const pending: PendingImageCallback[] = []

  if (!content || !content.children) {
    return pending
  }

  // Get paragraph-set (first child)
  const paragraphSet = content.children[0]
  if (!paragraphSet || !paragraphSet.children) {
    return pending
  }

  const paragraphs = paragraphSet.children

  // Extract fill images from all spans
  for (const paragraph of paragraphs) {
    if (!paragraph.children) {
      continue
    }
    for (const span of paragraph.children) {
      const fillImages = getFillImages(span)
      for (const fill of fillImages) {
        const callback = processFillImage(module, shapeId, fill, thumbnail, resolveImageUrl)
        if (callback) {
          pending.push(callback)
        }
      }
    }
  }

  return pending
}

/**
 * Detects if text contains emoji
 */
function containsEmoji(text: string): boolean {
  // Basic emoji detection using Unicode ranges
  const emojiRegex = /[\u{1F300}-\u{1F9FF}]|[\u{2600}-\u{26FF}]|[\u{2700}-\u{27BF}]|[\u{1F600}-\u{1F64F}]|[\u{1F680}-\u{1F6FF}]|[\u{1F1E0}-\u{1F1FF}]/u
  return emojiRegex.test(text)
}

/**
 * Collects languages used in text
 * Simplified version - would need proper language detection
 */
function collectLanguages(text: string, existingLangs: Set<string>): Set<string> {
  const langs = new Set(existingLangs)
  
  // Basic language detection - check for common scripts
  // This is simplified - full implementation would use proper language detection
  if (/[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FAF]/.test(text)) {
    langs.add('japanese')
  }
  if (/[\u4E00-\u9FFF]/.test(text)) {
    langs.add('chinese')
  }
  if (/[\uAC00-\uD7AF]/.test(text)) {
    langs.add('korean')
  }
  if (/[\u0600-\u06FF]/.test(text)) {
    langs.add('arabic')
  }
  
  return langs
}

/**
 * Adds emoji font if needed
 */
function addEmojiFont(fonts: FontInfo[]): FontInfo[] {
  return [
    ...fonts,
    {
      fontId: 'gfont-noto-color-emoji',
      fontVariantId: 'regular',
      fontWeight: 400,
      fontStyle: 'normal',
      isEmoji: true,
      isFallback: true,
    },
  ]
}

/**
 * Adds Noto fonts for detected languages
 */
function addNotoFonts(fonts: FontInfo[], languages: Set<string>): FontInfo[] {
  const notoFonts: Record<string, FontInfo> = {
    japanese: { fontId: 'gfont-noto-sans-jp', fontVariantId: 'regular', fontWeight: 400, fontStyle: 'normal', isFallback: true },
    chinese: { fontId: 'gfont-noto-sans-sc', fontVariantId: 'regular', fontWeight: 400, fontStyle: 'normal', isFallback: true },
    korean: { fontId: 'gfont-noto-sans-kr', fontVariantId: 'regular', fontWeight: 400, fontStyle: 'normal', isFallback: true },
    arabic: { fontId: 'gfont-noto-sans-arabic', fontVariantId: 'regular', fontWeight: 400, fontStyle: 'normal', isFallback: true },
  }

  const result = [...fonts]
  for (const lang of languages) {
    if (notoFonts[lang] && !result.some((f) => f.fontId === notoFonts[lang].fontId)) {
      result.push(notoFonts[lang])
    }
  }
  return result
}

/**
 * Extracts font requirements from text content
 * Detects emoji and languages, adds appropriate fallback fonts
 */
export function fontsFromTextContent(
  content: TextContent,
  fallbackFontsOnly: boolean = false
): FontInfo[] {
  if (!content || !content.children) {
    return []
  }

  const paragraphSet = content.children[0]
  if (!paragraphSet || !paragraphSet.children) {
    return []
  }

  const paragraphs = paragraphSet.children
  let hasEmoji = false
  const languages = new Set<string>()

  for (const paragraph of paragraphs) {
    if (!paragraph.children) {
      continue
    }

    const text = paragraph.children.map((span: any) => span.text ?? '').join('')

    if (text) {
      if (!hasEmoji) {
        hasEmoji = containsEmoji(text)
      }
      collectLanguages(text, languages)
    }
  }

  let updatedFonts: FontInfo[] = []

  if (hasEmoji) {
    updatedFonts = addEmojiFont(updatedFonts)
  }

  if (languages.size > 0) {
    updatedFonts = addNotoFonts(updatedFonts, languages)
  }

  if (fallbackFontsOnly) {
    return updatedFonts
  }
  return updatedFonts.filter((f) => f.isFallback === true)
}

/**
 * Updates text layouts for text shapes
 */
export function updateTextLayouts(module: WasmModule, shapes: PenpotNode[]): void {
  checkContext(module)
  
  for (const shape of shapes) {
    if (shape.type === 'text' && shape.id) {
      moduleUseShape(module, shape.id)
      module._update_shape_text_layout()
    }
  }
}

/**
 * Updates text rectangle dimensions
 * TODO: May need external callback for updating text rect in application state
 */
export function updateTextRect(
  module: WasmModule,
  id: string,
  onUpdate?: (id: string, dimensions: ReturnType<typeof getTextDimensions>) => void
): void {
  checkContext(module)
  
  const dimensions = getTextDimensions(module, id)
  
  if (onUpdate) {
    onUpdate(id, dimensions)
  }
}

/**
 * Position data entry interface
 */
export interface PositionDataEntry {
  paragraph: number
  span: number
  'start-pos': number
  'end-pos': number
  x: number
  y: number
  width: number
  height: number
  direction: number
}

/**
 * Calculates position data for text shapes
 */
export function calculatePositionData(module: WasmModule, shape: PenpotNode): PositionDataEntry[] {
  checkContext(module)
  
  if (shape.type !== 'text' || !shape.id) {
    return []
  }

  moduleUseShape(module, shape.id)
  
  const offset = offset8To32(module._calculate_position_data())
  const heapU32 = module.HEAPU32
  const heapF32 = module.HEAPF32
  
  const length = heapU32[offset]
  const maxOffset = offset + 1 + length * POSITION_DATA_U32_SIZE
  
  const result: PositionDataEntry[] = []
  let readOffset = offset + 1
  
  while (readOffset < maxOffset) {
    const entry: PositionDataEntry = {
      paragraph: heapU32[readOffset],
      span: heapU32[readOffset + 1],
      'start-pos': heapU32[readOffset + 2],
      'end-pos': heapU32[readOffset + 3],
      x: heapF32[readOffset + 4],
      y: heapF32[readOffset + 5],
      width: heapF32[readOffset + 6],
      height: heapF32[readOffset + 7],
      direction: heapU32[readOffset + 8],
    }
    result.push(entry)
    readOffset += POSITION_DATA_U32_SIZE
  }
  
  freeBytes(module)
  return result
}

