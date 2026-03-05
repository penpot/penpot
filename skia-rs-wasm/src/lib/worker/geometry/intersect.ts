/**
 * Overlap detection algorithms for shapes
 */

import type {
  Point,
  PenpotNode,
  Selrect,
  BoolShape,
  PathShape,
  TextShape,
  Matrix,
} from 'penpot-exporter/types'
import type { Line } from '../types'
import { makeSelrect } from '@skia-rs-wasm/common'
import { rectToPoints } from './rect'
import { point } from './point'

/** Shape with ellipse geometry (CircleShape or synthetic bounds for stroke band). */
type EllipseGeometry = {
  x: number
  y: number
  width: number
  height: number
  selrect?: Selrect
  transform?: Matrix
}

function inverseTransformPoint(world: Point, t: Matrix): Point {
  const det = t.a * t.d - t.b * t.c
  if (Math.abs(det) < EPSILON) return world
  const px = world.x - t.e
  const py = world.y - t.f
  return point(
    (t.d * px - t.c * py) / det,
    (-t.b * px + t.a * py) / det
  )
}

const EPSILON = 1e-10

function almostZero(value: number): boolean {
  return Math.abs(value) < EPSILON
}

function isIdentityTransform(t: Matrix | null | undefined): boolean {
  if (!t) return true
  return (
    Math.abs(t.a - 1) < EPSILON &&
    Math.abs(t.b) < EPSILON &&
    Math.abs(t.c) < EPSILON &&
    Math.abs(t.d - 1) < EPSILON &&
    Math.abs(t.e) < EPSILON &&
    Math.abs(t.f) < EPSILON
  )
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

function overlapsPath(shape: PathShape | BoolShape, rect: Selrect, includeContent: boolean): boolean {
  const content = 'content' in shape ? shape.content : undefined
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

function overlapsEllipse(shape: EllipseGeometry, rect: Selrect): boolean {
  const x = shape.x
  const y = shape.y
  const width = shape.width
  const height = shape.height

  const rx = width / 2
  const ry = height / 2

  const transform = shape.transform

  let rectPoints = rectToPoints(rect)
  if (!rectPoints) {
    return false
  }

  // Ellipse center: in local space (no transform) it is (x+w/2, y+h/2); with transform we use local origin at center so (0,0).
  let cx: number
  let cy: number
  let center: Point

  if (transform) {
    // World = T(local) + world_center. Use selrect center.
    const sr = shape.selrect
    const worldCenterX =
      sr != null && typeof sr.x === 'number' && typeof sr.width === 'number'
        ? sr.x + sr.width / 2
        : x + width / 2
    const worldCenterY =
      sr != null && typeof sr.y === 'number' && typeof sr.height === 'number'
        ? sr.y + sr.height / 2
        : y + height / 2
    const det = transform.a * transform.d - transform.b * transform.c
    if (Math.abs(det) < EPSILON) return false
    // inverseTransformPoint(p, M) applies M^{-1} to p. We need local = T^{-1}(world - center), so pass
    // the forward transform (e=0, f=0) so the function applies T_2x2^{-1}; passing inv would apply inv^{-1} = T (wrong direction).
    const transformLinear = { ...transform, e: 0, f: 0 }
    rectPoints = rectPoints.map((p) =>
      inverseTransformPoint(point(p.x - worldCenterX, p.y - worldCenterY), transformLinear)
    )
    cx = 0
    cy = 0
    center = point(0, 0)
  } else {
    cx = x + width / 2
    cy = y + height / 2
    center = point(cx, cy)
  }

  const rectLines = pointsToLines(rectPoints)

  // Check if center is inside rect
  if (isPointInsideEvenOdd(center, rectLines)) {
    return true
  }

  // Check if any rect point is inside ellipse (all four corners, not just the first)
  for (const pt of rectPoints) {
    if (isPointInsideEllipse(pt, cx, cy, rx, ry)) {
      return true
    }
  }

  // Check if any rect line intersects ellipse
  for (const line of rectLines) {
    if (intersectsLineEllipse(line, cx, cy, rx, ry)) return true
  }

  return false
}

function overlapsText(shape: TextShape, rect: Selrect): boolean {
  const positionData = shape.positionData
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

/** Get points from shape for overlap: use shape.points or derive from selrect. */
function getShapePointsForOverlap(shape: PenpotNode): Point[] {
  if (shape.points && shape.points.length > 0) {
    return shape.points
  }
  const sr = shape.selrect
  if (!sr) return []
  const x = sr.x
  const y = sr.y
  const width = sr.width ?? (typeof sr.x2 === 'number' && typeof sr.x1 === 'number' ? sr.x2 - sr.x1 : 0)
  const height = sr.height ?? (typeof sr.y2 === 'number' && typeof sr.y1 === 'number' ? sr.y2 - sr.y1 : 0)
  if (typeof x !== 'number' || typeof y !== 'number' || width <= 0 || height <= 0) return []
  const rect = makeSelrect(x, y, width, height)
  const pts = rectToPoints(rect)
  return pts ?? []
}

/** Outer padding (expansion): center → strokeWidth, outer → 2*strokeWidth, inner → 0. Max across strokes. */
function getStrokePaddingOuter(shape: PenpotNode): number {
  const strokes = shape.strokes
  if (!strokes || strokes.length === 0) return 0
  let max = 0
  for (const s of strokes) {
    const w = s.strokeWidth ?? 0
    const align = s.strokeAlignment ?? 'center'
    const padding =
      align === 'center' ? w : align === 'outer' ? 2 * w : 0
    if (padding > max) max = padding
  }
  return max
}

/** Inner padding (shrink): center → strokeWidth, outer → 0, inner → 2*strokeWidth. Max across strokes. */
function getStrokePaddingInner(shape: PenpotNode): number {
  const strokes = shape.strokes
  if (!strokes || strokes.length === 0) return 0
  let max = 0
  for (const s of strokes) {
    const w = s.strokeWidth ?? 0
    const align = s.strokeAlignment ?? 'center'
    const padding =
      align === 'center' ? w : align === 'inner' ? 2 * w : 0
    if (padding > max) max = padding
  }
  return max
}

/** Test if point (center of rect) is inside axis-aligned rect in local space. */
function isPointInLocalRect(
  localX: number,
  localY: number,
  halfW: number,
  halfH: number
): boolean {
  return Math.abs(localX) <= halfW && Math.abs(localY) <= halfH
}

function overlapsOuterShape(shape: PenpotNode, rect: Selrect, shapeType: string): boolean {
  const bounds = shape.selrect
  if (!bounds) return false
  const padding = getStrokePaddingOuter(shape)
  const centerX = bounds.x + bounds.width / 2
  const centerY = bounds.y + bounds.height / 2
  const w = bounds.width + padding
  const h = bounds.height + padding
  const outerX = centerX - w / 2
  const outerY = centerY - h / 2

  if (shapeType === 'rect') {
    const transform = shape.transform
    if (transform && !isIdentityTransform(transform)) {
      // Rotated rect: transform click point to local space and test against local axis-aligned stroke band
      const clickX = rect.x + rect.width / 2
      const clickY = rect.y + rect.height / 2
      const worldRel = point(clickX - centerX, clickY - centerY)
      const transformLinear = { ...transform, e: 0, f: 0 }
      const local = inverseTransformPoint(worldRel, transformLinear)
      const halfW = bounds.width / 2 + padding / 2
      const halfH = bounds.height / 2 + padding / 2
      return isPointInLocalRect(local.x, local.y, halfW, halfH)
    }
    const outerRect = makeSelrect(outerX, outerY, w, h)
    const outerPoints = rectToPoints(outerRect)
    if (!outerPoints) return false
    return overlapsRectPoints(rect, outerPoints)
  }

  if (shapeType === 'circle') {
    const synthetic: EllipseGeometry = {
      x: outerX,
      y: outerY,
      width: w,
      height: h,
      selrect: makeSelrect(outerX, outerY, w, h),
      transform: shape.transform,
    }
    return overlapsEllipse(synthetic, rect)
  }

  return false
}

function overlapsInnerShape(shape: PenpotNode, rect: Selrect, shapeType: string): boolean {
  const bounds = shape.selrect
  if (!bounds) return false
  const padding = getStrokePaddingInner(shape)
  const w = bounds.width - padding
  const h = bounds.height - padding
  if (w <= 0 || h <= 0) return false
  const centerX = bounds.x + bounds.width / 2
  const centerY = bounds.y + bounds.height / 2
  const innerX = centerX - w / 2
  const innerY = centerY - h / 2

  if (shapeType === 'rect') {
    const transform = shape.transform
    if (transform && !isIdentityTransform(transform)) {
      // Rotated rect: transform click point to local space and test against local inner band
      const clickX = rect.x + rect.width / 2
      const clickY = rect.y + rect.height / 2
      const worldRel = point(clickX - centerX, clickY - centerY)
      const transformLinear = { ...transform, e: 0, f: 0 }
      const local = inverseTransformPoint(worldRel, transformLinear)
      const halfW = bounds.width / 2 - padding / 2
      const halfH = bounds.height / 2 - padding / 2
      return isPointInLocalRect(local.x, local.y, halfW, halfH)
    }
    const innerRect = makeSelrect(innerX, innerY, w, h)
    const innerPoints = rectToPoints(innerRect)
    if (!innerPoints) return false
    return overlapsRectPoints(rect, innerPoints)
  }

  if (shapeType === 'circle') {
    const synthetic: EllipseGeometry = {
      x: innerX,
      y: innerY,
      width: w,
      height: h,
      selrect: makeSelrect(innerX, innerY, w, h),
      transform: shape.transform,
    }
    return overlapsEllipse(synthetic, rect)
  }

  return false
}

export function overlaps(shape: PenpotNode, rect: Selrect, usingSelrect: boolean = false): boolean {
  if (!shape) {
    return false
  }

  // Adjust rect for stroke width
  const firstStroke = shape.strokes?.[0]
  const strokeWidth = firstStroke?.strokeWidth ?? 0
  const swidth = strokeWidth / 2
  const adjustedRect: Selrect = makeSelrect(
    rect.x - swidth,
    rect.y - swidth,
    rect.width + 2 * swidth,
    rect.height + 2 * swidth
  )

  // Handle shapes without fills (stroke-only)
  const svgAttrs = shape.svgAttrs
  if (
    !usingSelrect &&
    (!shape.fills || shape.fills.length === 0) &&
    !svgAttrs?.fill &&
    !svgAttrs?.style?.fill
  ) {
    const shapeTypeInner = shape.type

    if (shapeTypeInner === 'rect' || shapeTypeInner === 'circle') {
      // Use click point (center of query rect) for stroke-band test so a large query rect
      // doesn't overlap the inner area and prevent selection (log evidence: H2).
      const centerX = adjustedRect.x + adjustedRect.width / 2
      const centerY = adjustedRect.y + adjustedRect.height / 2
      const eps = 1e-6
      const centerRect = makeSelrect(centerX - eps / 2, centerY - eps / 2, eps, eps)
      return (
        overlapsOuterShape(shape, centerRect, shapeTypeInner) &&
        !overlapsInnerShape(shape, centerRect, shapeTypeInner)
      )
    }

    if (shapeTypeInner === 'path' || shapeTypeInner === 'bool') {
      return overlapsPath(shape, adjustedRect, false)
    }
  }

  // Per-shape overlap pipelines (switch narrows type, no casts needed)
  switch (shape.type) {
    case 'path':
    case 'bool': {
      const points = shape.points || []
      return (
        overlapsRectPoints(adjustedRect, points) &&
        overlapsPath(shape, adjustedRect, true)
      )
    }
    case 'circle':
      return overlapsEllipse(shape, adjustedRect)
    case 'text':
      return overlapsText(shape, adjustedRect)
    default: {
      const points = getShapePointsForOverlap(shape)
      return points.length > 0 && overlapsRectPoints(adjustedRect, points)
    }
  }
}

