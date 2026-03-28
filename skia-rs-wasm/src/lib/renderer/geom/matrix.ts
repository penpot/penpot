/**
 * 2D affine matrix utilities.
 */

import type { Matrix } from 'penpot-exporter/types'

export function translateMatrix(dx: number, dy: number): Matrix {
  return { a: 1, b: 0, c: 0, d: 1, e: dx, f: dy }
}

/** Rotation (degrees CCW) around world point (cx, cy); matches WASM modifier convention. */
export function rotationMatrixAroundPoint(cx: number, cy: number, angleDeg: number): Matrix {
  const theta = (angleDeg * Math.PI) / 180
  const cos = Math.cos(theta)
  const sin = Math.sin(theta)
  return {
    a: cos,
    b: sin,
    c: -sin,
    d: cos,
    e: cx * (1 - cos) + cy * sin,
    f: cy * (1 - cos) - cx * sin,
  }
}

/** Full 6-component inverse matching Clojure's gmt/inverse. Returns null if singular. */
export function invertMatrix(T: Matrix): Matrix | null {
  const det = T.a * T.d - T.b * T.c
  if (Math.abs(det) < 1e-10) return null
  return {
    a: T.d / det,
    b: -T.b / det,
    c: -T.c / det,
    d: T.a / det,
    e: (T.c * T.f - T.d * T.e) / det,
    f: (T.b * T.e - T.a * T.f) / det,
  }
}
