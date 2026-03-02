/**
 * Shared types for renderer and worker.
 * Only types used by both sides live here; worker and renderer types live in their respective modules.
 */

/** Viewport coordinate point */
export interface Point {
  x: number
  y: number
}

export type Line = [Point, Point]
