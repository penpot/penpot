/**
 * Node Factory Utilities
 * Provides factory functions to create PenpotNode instances with proper defaults
 */

import type { ShapeType } from '../lib/renderer/types'
import type { PenpotNode, Selrect } from 'penpot-exporter/lib'
import type { Fill, Stroke } from 'penpot-exporter/lib'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

/**
 * Generates a random UUID v4
 */
function generateUUID(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/**
 * Creates a selrect from position and size
 */
function createSelRect(x: number, y: number, width: number, height: number): Selrect {
  return {
    x,
    y,
    width,
    height,
    x1: x,
    y1: y,
    x2: x + width,
    y2: y + height,
  }
}

/** Corner points for a rect (used by worker selection/overlap). */
function rectPoints(x: number, y: number, width: number, height: number) {
  return [
    { x, y },
    { x: x + width, y },
    { x: x + width, y: y + height },
    { x, y: y + height },
  ]
}

/**
 * Creates a rectangle node
 */
export function createRect(
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    parentId?: string
    fillColor?: string
    fillOpacity?: number
    strokeColor?: string
    strokeWidth?: number
    borderRadius?: number
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const x = options.x ?? 100
  const y = options.y ?? 100
  const width = options.width ?? 200
  const height = options.height ?? 150

  const fills: Fill[] = options.fillColor
    ? [
        {
          fillColor: options.fillColor,
          fillOpacity: options.fillOpacity ?? 1,
        },
      ]
    : []

  const strokes: Stroke[] = options.strokeColor
    ? [
        {
          strokeColor: options.strokeColor,
          strokeOpacity: 1,
          strokeWidth: options.strokeWidth ?? 2,
          strokeStyle: 'solid',
          strokeAlignment: 'center',
        },
      ]
    : []

  const node: PenpotNode = {
    id,
    type: 'rect',
    x,
    y,
    width,
    height,
    parentId: options.parentId ?? ROOT_UUID,
    selrect: createSelRect(x, y, width, height),
    points: rectPoints(x, y, width, height),
    fills,
    strokes,
    opacity: options.opacity ?? 1,
  }

  if (options.borderRadius !== undefined) {
    node.r1 = options.borderRadius
    node.r2 = options.borderRadius
    node.r3 = options.borderRadius
    node.r4 = options.borderRadius
  }

  return node
}

/**
 * Creates a circle node
 */
export function createCircle(
  options: {
    id?: string
    x?: number
    y?: number
    radius?: number
    parentId?: string
    fillColor?: string
    fillOpacity?: number
    strokeColor?: string
    strokeWidth?: number
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const radius = options.radius ?? 50
  const x = options.x ?? 100
  const y = options.y ?? 100
  const width = radius * 2
  const height = radius * 2

  const fills: Fill[] = options.fillColor
    ? [
        {
          fillColor: options.fillColor,
          fillOpacity: options.fillOpacity ?? 1,
        },
      ]
    : []

  const strokes: Stroke[] = options.strokeColor
    ? [
        {
          strokeColor: options.strokeColor,
          strokeOpacity: 1,
          strokeWidth: options.strokeWidth ?? 2,
          strokeStyle: 'solid',
          strokeAlignment: 'center',
        },
      ]
    : []

  return {
    id,
    type: 'circle',
    x,
    y,
    width,
    height,
    parentId: options.parentId ?? ROOT_UUID,
    selrect: createSelRect(x, y, width, height),
    fills,
    strokes,
    opacity: options.opacity ?? 1,
  }
}

/**
 * Creates a text node
 */
export function createText(
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    text?: string
    parentId?: string
    fillColor?: string
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const x = options.x ?? 100
  const y = options.y ?? 100
  const width = options.width ?? 200
  const height = options.height ?? 50

  const fills: Fill[] = options.fillColor
    ? [
        {
          fillColor: options.fillColor,
          fillOpacity: 1,
        },
      ]
    : [
        {
          fillColor: '#000000',
          fillOpacity: 1,
        },
      ]

  return {
    id,
    type: 'text',
    x,
    y,
    width,
    height,
    parentId: options.parentId ?? ROOT_UUID,
    selrect: createSelRect(x, y, width, height),
    fills,
    content: {
      verticalAlign: 'top',
      children: [
        {
          type: 'paragraph',
          children: [
            {
              type: 'text',
              text: options.text || 'Hello World',
            },
          ],
        },
      ],
    },
    opacity: options.opacity ?? 1,
  }
}

/**
 * Creates a frame node
 */
export function createFrame(
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    parentId?: string
    fillColor?: string
    fillOpacity?: number
    shapes?: string[]
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const x = options.x ?? 0
  const y = options.y ?? 0
  const width = options.width ?? 400
  const height = options.height ?? 300

  const fills: Fill[] = options.fillColor
    ? [
        {
          fillColor: options.fillColor,
          fillOpacity: options.fillOpacity ?? 0.1,
        },
      ]
    : []

  return {
    id,
    type: 'frame',
    x,
    y,
    width,
    height,
    parentId: options.parentId,
    shapes: options.shapes || [],
    selrect: createSelRect(x, y, width, height),
    fills,
    opacity: options.opacity ?? 1,
  }
}

/**
 * Creates a group node
 */
export function createGroup(
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    parentId?: string
    shapes?: string[]
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const x = options.x ?? 0
  const y = options.y ?? 0
  const width = options.width ?? 200
  const height = options.height ?? 200

  return {
    id,
    type: 'group',
    x,
    y,
    width,
    height,
    parentId: options.parentId ?? ROOT_UUID,
    shapes: options.shapes || [],
    selrect: createSelRect(x, y, width, height),
    opacity: options.opacity ?? 1,
  }
}

/**
 * Creates a path node (simplified - just a basic path)
 */
export function createPath(
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    parentId?: string
    fillColor?: string
    fillOpacity?: number
    strokeColor?: string
    strokeWidth?: number
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const x = options.x ?? 100
  const y = options.y ?? 100
  const width = options.width ?? 200
  const height = options.height ?? 200

  const fills: Fill[] = options.fillColor
    ? [
        {
          fillColor: options.fillColor,
          fillOpacity: options.fillOpacity ?? 1,
        },
      ]
    : []

  const strokes: Stroke[] = options.strokeColor
    ? [
        {
          strokeColor: options.strokeColor,
          strokeOpacity: 1,
          strokeWidth: options.strokeWidth ?? 2,
          strokeStyle: 'solid',
          strokeAlignment: 'center',
        },
      ]
    : []

  // Simple path content - a basic rectangle path
  const pathContent = {
    segments: [
      { type: 'move-to', x: 0, y: 0 },
      { type: 'line-to', x: width, y: 0 },
      { type: 'line-to', x: width, y: height },
      { type: 'line-to', x: 0, y: height },
      { type: 'close-path' },
    ],
  }

  return {
    id,
    type: 'path',
    x,
    y,
    width,
    height,
    parentId: options.parentId ?? ROOT_UUID,
    selrect: createSelRect(x, y, width, height),
    fills,
    strokes,
    content: pathContent,
    opacity: options.opacity ?? 1,
  }
}

/**
 * Creates a boolean operation node
 */
export function createBool(
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    parentId?: string
    boolType?: 'union' | 'difference' | 'intersection' | 'exclude'
    shapes?: string[]
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const x = options.x ?? 0
  const y = options.y ?? 0
  const width = options.width ?? 200
  const height = options.height ?? 200

  return {
    id,
    type: 'bool',
    x,
    y,
    width,
    height,
    parentId: options.parentId ?? ROOT_UUID,
    shapes: options.shapes || [],
    boolType: options.boolType ?? 'union',
    selrect: createSelRect(x, y, width, height),
    opacity: options.opacity ?? 1,
  }
}

/**
 * Creates an image node
 */
export function createImage(
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    parentId?: string
    imageId?: string
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const x = options.x ?? 100
  const y = options.y ?? 100
  const width = options.width ?? 200
  const height = options.height ?? 200

  const fills: Fill[] = options.imageId
    ? [
        {
          fillImage: {
            id: options.imageId,
            width,
            height,
          },
          fillOpacity: options.opacity ?? 1,
        },
      ]
    : []

  return {
    id,
    type: 'image',
    x,
    y,
    width,
    height,
    parentId: options.parentId ?? ROOT_UUID,
    selrect: createSelRect(x, y, width, height),
    fills,
    opacity: options.opacity ?? 1,
  }
}

/**
 * Creates an SVG raw node
 */
export function createSvgRaw(
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    parentId?: string
    svgContent?: string
    opacity?: number
  } = {}
): PenpotNode {
  const id = options.id || generateUUID()
  const x = options.x ?? 100
  const y = options.y ?? 100
  const width = options.width ?? 200
  const height = options.height ?? 200

  return {
    id,
    type: 'svg-raw',
    x,
    y,
    width,
    height,
    parentId: options.parentId ?? ROOT_UUID,
    selrect: createSelRect(x, y, width, height),
    content: options.svgContent || '<svg><rect width="100%" height="100%" fill="blue"/></svg>',
    opacity: options.opacity ?? 1,
  }
}

/**
 * Main factory function to create nodes by type
 */
export function createNode(
  type: ShapeType,
  options: {
    id?: string
    x?: number
    y?: number
    width?: number
    height?: number
    parentId?: string
    [key: string]: unknown
  } = {}
): PenpotNode {
  switch (type) {
    case 'rect':
      return createRect(options)
    case 'circle':
      return createCircle(options)
    case 'text':
      return createText(options)
    case 'frame':
      return createFrame(options)
    case 'group':
      return createGroup(options)
    case 'path':
      return createPath(options)
    case 'bool':
      return createBool(options)
    case 'image':
      return createImage(options)
    case 'svg-raw':
      return createSvgRaw(options)
    default:
      throw new Error(`Unknown node type: ${type}`)
  }
}

/**
 * Creates the root frame node (required by renderer)
 */
export function createRootFrame(width: number = 800, height: number = 600): PenpotNode {
  return createFrame({
    id: ROOT_UUID,
    x: 0,
    y: 0,
    width,
    height,
    shapes: [],
  })
}

