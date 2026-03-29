/**
 * Axis-aligned rect type for selection overlay drawing helpers.
 */

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

/**
 * Returns the axis-aligned bounding box of a rectangle rotated around its center.
 * If rotation is missing or 0, returns the original rect.
 */
export function getAABBOfRotatedRect(
  x: number,
  y: number,
  width: number,
  height: number,
  rotationDeg: number | undefined
): Rect {
  if (rotationDeg === undefined || rotationDeg === 0) {
    return { x, y, width, height }
  }
  const cx = x + width / 2
  const cy = y + height / 2
  const theta = (rotationDeg * Math.PI) / 180
  const cos = Math.cos(theta)
  const sin = Math.sin(theta)
  const rotate = (px: number, py: number) => ({
    x: cx + (px - cx) * cos - (py - cy) * sin,
    y: cy + (px - cx) * sin + (py - cy) * cos,
  })
  const corners = [
    rotate(x, y),
    rotate(x + width, y),
    rotate(x + width, y + height),
    rotate(x, y + height),
  ]
  const minX = Math.min(...corners.map((p) => p.x))
  const minY = Math.min(...corners.map((p) => p.y))
  const maxX = Math.max(...corners.map((p) => p.x))
  const maxY = Math.max(...corners.map((p) => p.y))
  return {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
  }
}
