/**
 * Rectangle utilities
 */

import type { Point, Selrect } from 'penpot-exporter/lib'
import { makeSelrect } from '../types'
import { point } from './point'

const EPSILON = 1e-10

function almostZero(value: number): boolean {
  return Math.abs(value) < EPSILON
}

function sEquals(a: number, b: number): boolean {
  return almostZero(a - b)
}

export function makeRect(x: number, y: number, width: number, height: number): Selrect {
  return makeSelrect(x, y, width, height)
}

export function rectToPoints(rect: Selrect): Point[] | null {
  const x = rect.x
  const y = rect.y
  const w = Math.max(rect.width || 0, 0.01)
  const h = Math.max(rect.height || 0, 0.01)

  if (typeof x !== 'number' || typeof y !== 'number') {
    return null
  }

  return [
    point(x, y),
    point(x + w, y),
    point(x + w, y + h),
    point(x, y + h),
  ]
}

export function pointsToRect(points: Point[]): Selrect | null {
  if (!points || points.length === 0) {
    return null
  }

  let minx = Infinity
  let miny = Infinity
  let maxx = -Infinity
  let maxy = -Infinity

  for (const pt of points) {
    const x = pt.x
    const y = pt.y
    if (typeof x === 'number' && typeof y === 'number') {
      minx = Math.min(minx, x)
      miny = Math.min(miny, y)
      maxx = Math.max(maxx, x)
      maxy = Math.max(maxy, y)
    }
  }

  if (!isFinite(minx) || !isFinite(miny) || !isFinite(maxx) || !isFinite(maxy)) {
    return null
  }

  return makeRect(minx, miny, maxx - minx, maxy - miny)
}

export function rectToCenter(rect: Selrect): Point | null {
  const x = rect.x
  const y = rect.y
  const w = rect.width
  const h = rect.height

  if (typeof x !== 'number' || typeof y !== 'number' || typeof w !== 'number' || typeof h !== 'number') {
    return null
  }

  return point(x + w / 2.0, y + h / 2.0)
}

export function centerToRect(center: Point, width: number, height?: number): Selrect | null {
  if (!center || typeof center.x !== 'number' || typeof center.y !== 'number') {
    return null
  }

  const h = height ?? width
  const x = center.x
  const y = center.y

  if (typeof width !== 'number' || typeof h !== 'number') {
    return null
  }

  return makeRect(x - width / 2, y - h / 2, width, h)
}

export function rectToLines(rect: Selrect): [Point, Point][] | null {
  const x = rect.x
  const y = rect.y
  const w = Math.max(rect.width || 0, 0.01)
  const h = Math.max(rect.height || 0, 0.01)

  if (typeof x !== 'number' || typeof y !== 'number') {
    return null
  }

  return [
    [point(x, y), point(x + w, y)],
    [point(x + w, y), point(x + w, y + h)],
    [point(x + w, y + h), point(x, y + h)],
    [point(x, y + h), point(x, y)],
  ]
}

export function joinRects(rects: Selrect[]): Selrect | null {
  if (!rects || rects.length === 0) {
    return null
  }

  let minx = Infinity
  let miny = Infinity
  let maxx = -Infinity
  let maxy = -Infinity

  for (const rect of rects) {
    const x = rect.x
    const y = rect.y
    const x2 = rect.x2 ?? (x + (rect.width || 0))
    const y2 = rect.y2 ?? (y + (rect.height || 0))

    if (typeof x === 'number' && typeof y === 'number' && typeof x2 === 'number' && typeof y2 === 'number') {
      minx = Math.min(minx, x)
      miny = Math.min(miny, y)
      maxx = Math.max(maxx, x2)
      maxy = Math.max(maxy, y2)
    }
  }

  if (!isFinite(minx) || !isFinite(miny) || !isFinite(maxx) || !isFinite(maxy)) {
    return null
  }

  return makeRect(minx, miny, maxx - minx, maxy - miny)
}

export function containsRect(rectA: Selrect, rectB: Selrect): boolean {
  const ax1 = rectA.x1 ?? rectA.x
  const ax2 = rectA.x2 ?? (rectA.x + (rectA.width || 0))
  const ay1 = rectA.y1 ?? rectA.y
  const ay2 = rectA.y2 ?? (rectA.y + (rectA.height || 0))

  const bx1 = rectB.x1 ?? rectB.x
  const bx2 = rectB.x2 ?? (rectB.x + (rectB.width || 0))
  const by1 = rectB.y1 ?? rectB.y
  const by2 = rectB.y2 ?? (rectB.y + (rectB.height || 0))

  return bx1 >= ax1 && bx2 <= ax2 && by1 >= ay1 && by2 <= ay2
}

export function containsPoint(rect: Selrect, pt: Point): boolean {
  const x1 = rect.x
  const y1 = rect.y
  const x2 = rect.x2 ?? (rect.x + (rect.width || 0))
  const y2 = rect.y2 ?? (rect.y + (rect.height || 0))

  const px = pt.x
  const py = pt.y

  return (px > x1 || sEquals(px, x1)) &&
         (px < x2 || sEquals(px, x2)) &&
         (py > y1 || sEquals(py, y1)) &&
         (py < y2 || sEquals(py, y2))
}

export function overlapsRects(rectA: Selrect, rectB: Selrect): boolean {
  const x1a = rectA.x
  const y1a = rectA.y
  const x2a = rectA.x2 ?? (rectA.x + (rectA.width || 0))
  const y2a = rectA.y2 ?? (rectA.y + (rectA.height || 0))

  const x1b = rectB.x
  const y1b = rectB.y
  const x2b = rectB.x2 ?? (rectB.x + (rectB.width || 0))
  const y2b = rectB.y2 ?? (rectB.y + (rectB.height || 0))

  return (x2a > x1b || sEquals(x2a, x1b)) &&
         (x2b >= x1a || sEquals(x2b, x1a)) &&
         (y1b <= y2a || sEquals(y1b, y2a)) &&
         (y1a <= y2b || sEquals(y1a, y2b))
}

