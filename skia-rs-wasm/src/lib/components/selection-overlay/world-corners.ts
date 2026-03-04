/**
 * World-space corners of the selection rect for drawing handles without applying
 * the selection transform (so corner squares and rotation hit areas stay axis-aligned).
 */

import type { SelectionRectResult } from '../../renderer/types'

export interface WorldCorner {
  x: number
  y: number
}

export interface SelectionWorldCorners {
  topLeft: WorldCorner
  topRight: WorldCorner
  bottomRight: WorldCorner
  bottomLeft: WorldCorner
}

/**
 * Returns the four world-space corners of the selection rect.
 * Formula: world = center + transform × local, with local corners at (±width/2, ±height/2).
 */
export function getSelectionWorldCorners(sel: SelectionRectResult): SelectionWorldCorners {
  const { center, width, height, transform } = sel
  const hw = width / 2
  const hh = height / 2
  const { a, b, c, d } = transform

  const transformLocal = (lx: number, ly: number): WorldCorner => ({
    x: center.x + a * lx + c * ly,
    y: center.y + b * lx + d * ly,
  })

  return {
    topLeft: transformLocal(-hw, -hh),
    topRight: transformLocal(hw, -hh),
    bottomRight: transformLocal(hw, hh),
    bottomLeft: transformLocal(-hw, hh),
  }
}
