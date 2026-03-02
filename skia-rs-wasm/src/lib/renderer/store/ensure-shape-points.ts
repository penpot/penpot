/**
 * Ensures every shape in a page has precomputed `points` (four corners in world space)
 * so the worker can assume shape.points and not recompute from selrect+transform.
 */

import type { PenpotNode, PenpotPage, Point, Selrect } from 'penpot-exporter/lib'
import type { Matrix } from 'penpot-exporter/lib'

const EPSILON = 1e-10

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

function transformPoint(pt: Point, t: Matrix): Point {
  return {
    x: t.a * pt.x + t.c * pt.y + t.e,
    y: t.b * pt.x + t.d * pt.y + t.f,
  }
}

/** Compute four world-space corners from shape selrect and optional transform. */
export function computePointsFromSelrectAndTransform(shape: PenpotNode): Point[] {
  const sr = shape.selrect as (Selrect & { x1?: number; y1?: number; x2?: number; y2?: number }) | null | undefined
  if (!sr) return []
  const x = sr.x ?? sr.x1
  const y = sr.y ?? sr.y1
  const width = sr.width ?? (typeof sr.x2 === 'number' && typeof sr.x1 === 'number' ? sr.x2 - sr.x1 : 0)
  const height = sr.height ?? (typeof sr.y2 === 'number' && typeof sr.y1 === 'number' ? sr.y2 - sr.y1 : 0)
  if (typeof x !== 'number' || typeof y !== 'number' || width <= 0 || height <= 0) return []

  const cx = x + width / 2
  const cy = y + height / 2
  const w2 = width / 2
  const h2 = height / 2
  const localCorners: Point[] = [
    { x: -w2, y: -h2 },
    { x: w2, y: -h2 },
    { x: w2, y: h2 },
    { x: -w2, y: h2 },
  ]

  const transform = (shape as { transform?: Matrix }).transform
  if (!transform || isIdentityTransform(transform)) {
    return localCorners.map((p) => ({ x: p.x + cx, y: p.y + cy }))
  }
  return localCorners.map((pt) => {
    const t = transformPoint(pt, transform)
    return { x: t.x + cx, y: t.y + cy }
  })
}

/** Ensure a single node has points; returns new node with points set if missing. */
function ensureShapePoints(node: PenpotNode): PenpotNode {
  const points = node.points
  if (points && points.length > 0) {
    return node
  }
  console.log('ensureShapePoints', node)
  const computed = computePointsFromSelrectAndTransform(node)
  if (computed.length === 0) return node
  return { ...node, points: computed }
}

function ensureNodePointsRecursive(node: PenpotNode): PenpotNode {
  const withPoints = ensureShapePoints(node)
  const childList = (node as { children?: PenpotNode[] }).children
  if (!childList?.length) return withPoints
  return { ...withPoints, children: childList.map(ensureNodePointsRecursive) } as PenpotNode
}

/** Walk page tree and ensure every node has points. Returns new page (clone-on-write). */
export function ensurePageShapePoints(page: PenpotPage): PenpotPage {
  const children = page.children
  if (!children?.length) return page
  return { ...page, children: children.map(ensureNodePointsRecursive) }
}
