/**
 * Shape geometry utilities
 */

import type {
  PenpotNode,
  Point,
  Selrect,
  TextShape,
  FrameShape,
  PathShape,
  CircleShape,
  BoolShape,
  GroupShape,
  ImageShape,
  SvgRawShape,
  RectShape,
  ComponentInstance,
  ComponentShape,
} from 'penpot-exporter/types'
import { pointsToRect, rectToCenter, joinRects } from './rect'
import { ZERO_UUID } from '../types'

export function shapesToRect(shapes: PenpotNode[]): Selrect | null {
  const rects = shapes
    .map(shape => {
      const points = shape.points
      if (points && points.length > 0) {
        return pointsToRect(points)
      }
      return null
    })
    .filter((rect): rect is Selrect => rect !== null)

  return joinRects(rects)
}

export function shapeToCenter(shape: PenpotNode): Point | null {
  const selrect = shape.selrect
  if (!selrect) {
    return null
  }
  return rectToCenter(selrect)
}

export function boundingBox(shape: PenpotNode): Selrect | null {
  const points = shape.points
  if (!points || points.length === 0) {
    return null
  }
  return pointsToRect(points)
}

export function rectContainsShape(rect: Selrect, shape: PenpotNode): boolean {
  const points = shape.points
  if (!points || points.length === 0) {
    return false
  }

  // Check if all points of the shape are inside the rect
  for (const point of points) {
    const px = point.x
    const py = point.y
    const x1 = rect.x
    const y1 = rect.y
    const x2 = rect.x2 ?? (rect.x + (rect.width || 0))
    const y2 = rect.y2 ?? (rect.y + (rect.height || 0))

    if (px < x1 || px > x2 || py < y1 || py > y2) {
      return false
    }
  }

  return true
}

// Shape type checking helpers (type predicates for narrowing)
export function isTextShape(shape: PenpotNode | null | undefined): shape is TextShape {
  return shape != null && shape.type === 'text'
}

export function isFrameShape(shape: PenpotNode | null | undefined): shape is FrameShape {
  return shape != null && shape.type === 'frame'
}

export function isPathShape(shape: PenpotNode | null | undefined): shape is PathShape {
  return shape != null && shape.type === 'path'
}

export function isCircleShape(shape: PenpotNode | null | undefined): shape is CircleShape {
  return shape != null && shape.type === 'circle'
}

export function isBoolShape(shape: PenpotNode | null | undefined): shape is BoolShape {
  return shape != null && shape.type === 'bool'
}

export function isGroupShape(shape: PenpotNode | null | undefined): shape is GroupShape {
  return shape != null && shape.type === 'group'
}

export function isImageShape(shape: PenpotNode | null | undefined): shape is ImageShape {
  return shape != null && shape.type === 'image'
}

export function isSvgRawShape(shape: PenpotNode | null | undefined): shape is SvgRawShape {
  return shape != null && shape.type === 'svg-raw'
}

export function isComponentInstance(shape: PenpotNode | null | undefined): shape is ComponentInstance {
  return shape != null && shape.type === 'instance'
}

export function isComponentShape(shape: PenpotNode | null | undefined): shape is ComponentShape {
  return shape != null && shape.type === 'component'
}

export function hasShapes(node: PenpotNode): node is PenpotNode & { shapes: string[] } {
  return 'shapes' in node && Array.isArray((node as { shapes?: unknown }).shapes)
}

export function isRootFrame(shape: PenpotNode | null | undefined): boolean {
  return (
    shape != null &&
    shape.type === 'frame' &&
    shape.id !== ZERO_UUID &&
    shape.frameId === ZERO_UUID
  )
}

export function isRectShape(shape: PenpotNode | null | undefined): shape is RectShape {
  return shape != null && shape.type === 'rect'
}

export function isDirectChildOfRoot(shape: PenpotNode | null | undefined): boolean {
  return shape != null && shape.frameId === ZERO_UUID
}

/** Node that may have a child-id list (frame, group, bool, etc.) */
type NodeWithShapes = PenpotNode & { shapes?: string[] }

export function getImmediateChildren(
  objects: Record<string, NodeWithShapes>,
  shapeId: string = ZERO_UUID
): PenpotNode[] {
  const shape = objects[shapeId]
  if (!shape || !shape.shapes) {
    return []
  }

  return shape.shapes
    .map((id: string) => objects[id])
    .filter(
      (child: PenpotNode | undefined): child is PenpotNode =>
        child != null && !child.hidden && !child.blocked
    )
}

