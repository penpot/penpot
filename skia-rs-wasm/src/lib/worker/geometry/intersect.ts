/**
 * Overlap detection algorithms for shapes
 */

import type { Point, PenpotNode, Selrect } from '@penpot-exporter/types'
import type { Line } from '@skia-rs-wasm/common'
import { makeSelrect } from '@skia-rs-wasm/common'
import { rectToPoints } from './rect'
import { point } from './point'
import { isPathShape, isBoolShape, isCircleShape, isTextShape } from './shapes'

const EPSILON = 1e-10

function almostZero(value: number): boolean {
  return Math.abs(value) < EPSILON
}

function sq(value: number): number {
  return value * value
}

function sqrt(value: number): number {
  return Math.sqrt(value)
}

type Orientation = 'clockwise' | 'counter-clockwise' | 'coplanar'

function orientation(p1: Point, p2: Point, p3: Point): Orientation {
  const v = (p2.y - p1.y) * (p3.x - p2.x) - (p3.y - p2.y) * (p2.x - p1.x)
  if (v > 0) return 'clockwise'
  if (v < 0) return 'counter-clockwise'
  return 'coplanar'
}

function onSegment(q: Point, p: Point, r: Point): boolean {
  return (
    q.x <= Math.max(p.x, r.x) &&
    q.x >= Math.min(p.x, r.x) &&
    q.y <= Math.max(p.y, r.y) &&
    q.y >= Math.min(p.y, r.y)
  )
}

function intersectSegments([p1, q1]: Line, [p2, q2]: Line): boolean {
  const o1 = orientation(p1, q1, p2)
  const o2 = orientation(p1, q1, q2)
  const o3 = orientation(p2, q2, p1)
  const o4 = orientation(p2, q2, q1)

  return (
    // General case
    (o1 !== o2 && o3 !== o4) ||
    // p1, q1 and p2 colinear and p2 lies on p1q1
    (o1 === 'coplanar' && onSegment(p2, p1, q1)) ||
    // p1, q1 and q2 colinear and q2 lies on p1q1
    (o2 === 'coplanar' && onSegment(q2, p1, q1)) ||
    // p2, q2 and p1 colinear and p1 lies on p2q2
    (o3 === 'coplanar' && onSegment(p1, p2, q2)) ||
    // p2, q2 and p1 colinear and q1 lies on p2q2
    (o4 === 'coplanar' && onSegment(q1, p2, q2))
  )
}

export function pointsToLines(points: Point[], closed: boolean = true): Line[] {
  if (points.length === 0) {
    return []
  }

  const lines: Line[] = []
  for (let i = 0; i < points.length; i++) {
    const next = closed && i === points.length - 1 ? 0 : i + 1
    if (next < points.length) {
      lines.push([points[i], points[next]])
    }
  }
  return lines
}

export function intersectsLines(linesA: Line[], linesB: Line[]): boolean {
  for (const curLine of linesA) {
    for (const lineB of linesB) {
      if (intersectSegments(curLine, lineB)) {
        return true
      }
    }
  }
  return false
}

function intersectRay(p: Point, [p1, p2]: Line): boolean {
  const { x: px, y: py } = p
  const { x: x1, y: y1 } = p1
  const { x: x2, y: y2 } = p2

  if ((y1 <= py && y2 > py) || (y1 > py && y2 <= py)) {
    const vt = (py - y1) / (y2 - y1)
    const ix = x1 + vt * (x2 - x1)
    return px < ix
  }

  return false
}

export function isPointInsideEvenOdd(p: Point, lines: Line[]): boolean {
  // Even-odd algorithm: cast a ray and count intersections
  // if odd, point is inside
  const intersections = lines.filter(line => intersectRay(p, line))
  return intersections.length % 2 === 1
}

function nextWindup(wn: number, p: Point, [p1, p2]: Line): number {
  const lineSide = (p2.x - p1.x) * (p.y - p1.y) - (p.x - p1.x) * (p2.y - p1.y)

  if (p1.y <= p.y) {
    // Upward crossing
    if (p2.y > p.y && lineSide > 0) {
      return wn + 1
    }
    return wn
  } else {
    // Downward crossing
    if (p2.y <= p.y && lineSide < 0) {
      return wn - 1
    }
    return wn
  }
}

function isPointInsideNonzero(p: Point, lines: Line[]): boolean {
  // Non-zero winding number
  let wn = 0
  for (const line of lines) {
    wn = nextWindup(wn, p, line)
  }
  return wn !== 0
}

export function overlapsRectPoints(rect: Selrect, points: Point[]): boolean {
  const rectPoints = rectToPoints(rect)
  if (!rectPoints || rectPoints.length === 0) {
    return false
  }

  const rectLines = pointsToLines(rectPoints)
  const pointsLines = pointsToLines(points)

  return (
    isPointInsideEvenOdd(rectPoints[0], pointsLines) ||
    isPointInsideEvenOdd(points[0], rectLines) ||
    intersectsLines(rectLines, pointsLines)
  )
}

function overlapsPath(shape: PenpotNode, rect: Selrect, includeContent: boolean): boolean {
  const content = shape.content
  if (!content || (Array.isArray(content) && content.length === 0)) {
    return false
  }

  // Simplified: use points for path overlap
  // Full implementation would need path segment parsing
  const points = shape.points
  if (!points || points.length === 0) {
    return false
  }

  const rectPoints = rectToPoints(rect)
  if (!rectPoints) {
    return false
  }

  const rectLines = pointsToLines(rectPoints)
  const pathLines = pointsToLines(points)

  if (intersectsLines(rectLines, pathLines)) {
    return true
  }

  if (includeContent) {
    return (
      isPointInsideNonzero(rectPoints[0], pathLines) ||
      (points.length > 0 && isPointInsideNonzero(points[0], rectLines))
    )
  }

  return false
}

function isPointInsideEllipse(
  pt: Point,
  cx: number,
  cy: number,
  rx: number,
  ry: number
): boolean {
  const v = sq(pt.x - cx) / sq(rx) + sq(pt.y - cy) / sq(ry)
  return v <= 1
}

function intersectsLineEllipse(
  [p1, p2]: Line,
  cx: number,
  cy: number,
  rx: number,
  ry: number
): boolean {
  const { x: x1, y: y1 } = p1
  const { x: x2, y: y2 } = p2

  const a = sq(x2 - x1) / sq(rx) + sq(y2 - y1) / sq(ry)
  const b =
    (2 * x1 * (x2 - x1) - 2 * cx * (x2 - x1)) / sq(rx) +
    (2 * y1 * (y2 - y1) - 2 * cy * (y2 - y1)) / sq(ry)
  const c =
    (sq(x1) + sq(cx) - 2 * x1 * cx) / sq(rx) +
    (sq(y1) + sq(cy) - 2 * y1 * cy) / sq(ry) -
    1

  const determ = sq(b) - 4 * a * c

  if (almostZero(a)) {
    if (almostZero(b)) {
      return false
    }
    const t = -c / b
    return t >= 0 && t <= 1
  }

  if (determ < 0) {
    return false
  }

  const t1 = (-b + sqrt(determ)) / (2 * a)
  const t2 = (-b - sqrt(determ)) / (2 * a)

  return (t1 >= 0 && t1 <= 1) || (t2 >= 0 && t2 <= 1)
}

function overlapsEllipse(shape: PenpotNode, rect: Selrect): boolean {
  const x = shape.x ?? 0
  const y = shape.y ?? 0
  const width = shape.width ?? 0
  const height = shape.height ?? 0

  const center = point(x + width / 2, y + height / 2)
  const cx = center.x
  const cy = center.y
  const rx = width / 2
  const ry = height / 2

  const rectPoints = rectToPoints(rect)
  if (!rectPoints) {
    return false
  }

  const rectLines = pointsToLines(rectPoints)

  // Check if center is inside rect
  if (isPointInsideEvenOdd(center, rectLines)) {
    return true
  }

  // Check if any rect point is inside ellipse
  if (isPointInsideEllipse(rectPoints[0], cx, cy, rx, ry)) {
    return true
  }

  // Check if any rect line intersects ellipse
  for (const line of rectLines) {
    if (intersectsLineEllipse(line, cx, cy, rx, ry)) {
      return true
    }
  }

  return false
}

function overlapsText(shape: PenpotNode, rect: Selrect): boolean {
  const positionData = shape['position-data']
  const points = shape.points

  // If shape has position data, use it (simplified - full impl would transform)
  if (positionData && Array.isArray(positionData) && positionData.length > 0) {
    // Simplified: fall back to points
    if (points && points.length > 0) {
      return overlapsRectPoints(rect, points)
    }
    return false
  }

  // Use points directly
  if (points && points.length > 0) {
    return overlapsRectPoints(rect, points)
  }

  return false
}

/** Get points from shape for overlap: use shape.points or derive from selrect (x1,y1,x2,y2 or x,y,width,height). */
function getShapePointsForOverlap(shape: PenpotNode): Point[] {
  if (shape.points && shape.points.length > 0) {
    return shape.points
  }
  const sr = shape.selrect as (Selrect & { x1?: number; y1?: number; x2?: number; y2?: number }) | null | undefined
  if (!sr) return []
  const x = sr.x ?? sr.x1
  const y = sr.y ?? sr.y1
  const width = sr.width ?? (typeof sr.x2 === 'number' && typeof sr.x1 === 'number' ? sr.x2 - sr.x1 : 0)
  const height = sr.height ?? (typeof sr.y2 === 'number' && typeof sr.y1 === 'number' ? sr.y2 - sr.y1 : 0)
  if (typeof x !== 'number' || typeof y !== 'number' || width <= 0 || height <= 0) return []
  const rect = makeSelrect(x, y, width, height)
  const pts = rectToPoints(rect)
  return pts ?? []
}

export function overlaps(shape: PenpotNode, rect: Selrect, usingSelrect: boolean = false): boolean {
  if (!shape) {
    return false
  }

  // Adjust rect for stroke width
  const strokeWidth = shape.strokes?.[0]?.['stroke-width'] ?? 0
  const swidth = strokeWidth / 2
  const adjustedRect: Selrect = makeSelrect(
    rect.x - swidth,
    rect.y - swidth,
    rect.width + 2 * swidth,
    rect.height + 2 * swidth
  )

  // Handle shapes without fills (stroke-only)
  if (
    !usingSelrect &&
    (!shape.fills || shape.fills.length === 0) &&
    !shape['svg-attrs']?.fill &&
    !shape['svg-attrs']?.style?.fill
  ) {
    const shapeType = shape.type

    if (shapeType === 'rect' || shapeType === 'circle') {
      return overlapsRectPoints(adjustedRect, getShapePointsForOverlap(shape))
    }

    if (shapeType === 'path' || shapeType === 'bool') {
      return overlapsPath(shape, adjustedRect, false)
    }
  }

  // Regular overlap detection
  if (isPathShape(shape) || isBoolShape(shape)) {
    const points = shape.points || []
    return (
      overlapsRectPoints(adjustedRect, points) &&
      overlapsPath(shape, adjustedRect, true)
    )
  }

  if (isCircleShape(shape)) {
    const points = getShapePointsForOverlap(shape)
    return overlapsRectPoints(adjustedRect, points) && overlapsEllipse(shape, adjustedRect)
  }

  if (isTextShape(shape)) {
    return overlapsText(shape, adjustedRect)
  }

  // Default: check rect points overlap (rect/circle etc. – use points or selrect-derived points)
  const points = getShapePointsForOverlap(shape)
  return points.length > 0 && overlapsRectPoints(adjustedRect, points)
}

