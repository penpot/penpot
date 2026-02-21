/**
 * Point utilities
 */

import type { Point } from '@penpot-exporter/types'

export function point(x: number, y: number): Point {
  return { x, y }
}

export function isPoint(p: any): p is Point {
  return p != null && typeof p.x === 'number' && typeof p.y === 'number'
}

export function pointLike(p: any): p is Point {
  return p != null && typeof p === 'object' && typeof p.x === 'number' && typeof p.y === 'number'
}

