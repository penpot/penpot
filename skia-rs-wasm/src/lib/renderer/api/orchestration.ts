/**
 * Higher-level orchestration functions
 */

import type { WasmModule } from '../wasm-types'
import type { PendingImageCallback, SetObjectResult } from '../types'
import type { PenpotNode } from '@penpot-exporter/types'
import { checkContext } from './context'
import { requestRender } from './rendering'
import { renderFinish } from './viewport'
import {
  moduleUseShape,
  setParentId,
  setShapeType,
  setShapeClipContent,
  setShapeConstraints,
  setShapeRotation,
  setShapeTransform,
  setShapeBlendMode,
  setShapeOpacity,
  setShapeHidden,
  setShapeChildren,
  setShapeCorners,
  setShapeBlur,
  setShapeBoolType,
  setShapeGrowType,
  setMasked,
  setShapeSelrect,
} from './shape'
import { setShapeFills } from './fills'
import { setShapeStrokes } from './strokes'
import { setShapeShadows } from './shadows'
import { setShapeSvgAttrs, setShapeSvgRawContent } from './svg'
import { setShapePathContent } from './path'
import {
  setFlexLayout,
  setGridLayout,
  setLayoutData,
  clearLayout,
} from './layout'
import {
  setShapeTextContent,
  setShapeTextImages,
  updateTextLayouts,
} from './text'
import { getStaticMarkup } from './utils'

/**
 * Ensures text content is valid, falling back to default if needed
 */
function ensureTextContent(content: any): any {
  // TODO: Implement default text content fallback
  return content || {
    'vertical-align': 'top',
    children: []
  }
}

/**
 * Sets all properties of a shape object
 * Returns pending image loading operations
 */
export function setObject(
  module: WasmModule,
  shape: PenpotNode,
  resolveImageUrl?: (imageId: string, thumbnail: boolean) => string
): SetObjectResult {
  checkContext(module)
  
  const id = shape.id
  const type = shape.type
  const parentId = shape.parentId
  const masked = shape.maskedGroup
  const selrect = shape.selrect
  const constraintH = shape.constraintsH
  const constraintV = shape.constraintsV
  const clipContent = type === 'frame' ? !shape.showContent : false
  const rotation = shape.rotation
  const transform = shape.transform
  const fills = shape.fills || []
  const strokes = type === 'group' ? [] : (shape.strokes || [])
  const children = shape.shapes || []
  const blendMode = shape.blendMode
  const opacity = shape.opacity
  const hidden = shape.hidden
  const content = type === 'text' ? ensureTextContent(shape.content) : shape.content
  const boolType = shape.boolType
  const growType = shape.growType
  const blur = shape.blur
  const svgAttrs = shape.svgAttrs
  const shadows = shape.shadow || []
  const corners: [number?, number?, number?, number?] = [
    shape.r1,
    shape.r2,
    shape.r3,
    shape.r4,
  ]

  // Set basic shape properties
  moduleUseShape(module, id)
  setParentId(module, parentId)
  setShapeType(module, type)
  setShapeClipContent(module, clipContent)
  setShapeConstraints(module, constraintH, constraintV)
  
  // Unconditional calls (matching ClojureScript)
  setShapeRotation(module, rotation)
  setShapeTransform(module, transform)
  setShapeBlendMode(module, blendMode)
  setShapeOpacity(module, opacity)
  setShapeHidden(module, hidden ?? false)
  setShapeChildren(module, children)
  setShapeCorners(module, corners)
  setShapeBlur(module, blur)

  // Type-specific properties
  if (type === 'group') {
    setMasked(module, masked ?? false)
  }
  if (type === 'bool') {
    setShapeBoolType(module, boolType)
  }
  if (content && (type === 'path' || type === 'bool')) {
    setShapePathContent(module, content)
  }
  if (svgAttrs) {
    setShapeSvgAttrs(module, svgAttrs)
  }
  if (content && type === 'svg-raw') {
    const markup = getStaticMarkup(shape)
    setShapeSvgRawContent(module, markup)
  }
  setShapeShadows(module, shadows)
  if (type === 'text') {
    setShapeGrowType(module, growType)
  }

  // Layout properties - always called (matching ClojureScript)
  clearLayout(module)
  if (shape.layoutFlexDir) {
    setFlexLayout(module, shape)
  }
  if (shape.layoutGridDir) {
    setGridLayout(module, shape)
  }
  setLayoutData(module, shape)
  
  // Selrect set after layout (matching ClojureScript order)
  setShapeSelrect(module, selrect || { x1: 0, y1: 0, x2: 0, y2: 0 })

  // Collect pending operations
  const pendingThumbnails: PendingImageCallback[] = []
  const pendingFull: PendingImageCallback[] = []

  // Text content and images - always call for text type
  if (type === 'text') {
    pendingThumbnails.push(...setShapeTextContent(module, id, content, resolveImageUrl))
    pendingThumbnails.push(...setShapeTextImages(module, id, content, true, resolveImageUrl))
    pendingFull.push(...setShapeTextImages(module, id, content, false, resolveImageUrl))
  }

  // Fills and strokes
  pendingThumbnails.push(...setShapeFills(module, id, fills, true, resolveImageUrl))
  pendingThumbnails.push(...setShapeStrokes(module, id, strokes, true))
  pendingFull.push(...setShapeFills(module, id, fills, false, resolveImageUrl))
  pendingFull.push(...setShapeStrokes(module, id, strokes, false))

  return {
    thumbnails: pendingThumbnails,
    full: pendingFull,
  }
}

/**
 * Processes pending image loading operations
 * Executes thumbnails first, then full images
 */
export async function processPending(
  module: WasmModule,
  shapes: PenpotNode[],
  thumbnails: PendingImageCallback[],
  full: PendingImageCallback[],
  onRender?: () => void,
  onComplete?: () => void
): Promise<void> {
  try {
    // Index by key to deduplicate
    const thumbnailMap = new Map<string, PendingImageCallback>()
    const fullMap = new Map<string, PendingImageCallback>()

    for (const callback of thumbnails) {
      thumbnailMap.set(callback.key, callback)
    }

    for (const callback of full) {
      fullMap.set(callback.key, callback)
    }

    // Process thumbnails first (in parallel)
    const thumbnailPromises = Array.from(thumbnailMap.values()).map(cb => cb.callback())
    await Promise.all(thumbnailPromises)

    // Process full images (in parallel)
    const fullPromises = Array.from(fullMap.values()).map(cb => cb.callback())
    await Promise.all(fullPromises)

    // Update text layouts
    updateTextLayouts(module, shapes)

    // Call render callback
    if (onRender) {
      onRender()
    } else {
      requestRender(module, 'pending-finished')
    }
  } catch (error) {
    console.error('Error processing pending operations:', error)
  } finally {
    if (onComplete) {
      onComplete()
    }
  }
}

/**
 * Convenience wrapper around setObject + processPending
 */
export async function processObject(
  module: WasmModule,
  shape: PenpotNode,
  resolveImageUrl?: (imageId: string, thumbnail: boolean) => string
): Promise<void> {
  const { thumbnails, full } = setObject(module, shape, resolveImageUrl)
  await processPending(module, [shape], thumbnails, full)
}

/**
 * Sets multiple objects and processes pending operations
 */
export async function setObjects(
  module: WasmModule,
  objects: Record<string, PenpotNode>,
  renderCallback?: () => void,
  resolveImageUrl?: (imageId: string, thumbnail: boolean) => string
): Promise<void> {
  checkContext(module)
  
  const shapes = Object.values(objects)
  const thumbnails: PendingImageCallback[] = []
  const full: PendingImageCallback[] = []

  // Set all objects and collect pending operations
  for (const shape of shapes) {
    const result = setObject(module, shape, resolveImageUrl)
    thumbnails.push(...result.thumbnails)
    full.push(...result.full)
  }

  // Process pending operations
  await processPending(
    module,
    shapes,
    thumbnails,
    full,
    renderCallback || (() => {
      renderFinish(module, performance.now())
    }),
    () => {
      // Optional: dispatch event if needed
      // dispatchEvent(new CustomEvent('penpot:wasm:set-objects'))
    }
  )
}

