/**
 * Point utilities
 */

import type { Point } from 'penpot-exporter/types'

export function point(x: number, y: number): Point {
  return { x, y }
}

export function isPoint(p: unknown): p is Point {
  return p != null && typeof (p as Point).x === 'number' && typeof (p as Point).y === 'number'
}

export function pointLike(p: unknown): p is Point {
  return p != null && typeof p === 'object' && typeof (p as Point).x === 'number' && typeof (p as Point).y === 'number'
}

