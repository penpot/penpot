/**
 * 2D affine matrix utilities.
 */

import type { Matrix } from 'penpot-exporter/types'

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
