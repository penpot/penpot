import type { Matrix } from '@penpot-exporter/types'
import type { ResizeHandlePosition } from '../../renderer/types'

/**
 * Rotation angle in degrees from a 2D affine transform matrix (direction of transformed x-axis).
 */
export function matrixToRotationDeg(transform: Matrix): number {
  return (Math.atan2(transform.b, transform.a) * 180) / Math.PI
}

export const SELECTION_STROKE = 'var(--color-accent-tertiary, #0d7377)'
export const SELECTION_STROKE_WIDTH = 1
export const HANDLE_FILL = 'var(--app-white, #fff)'
export const HANDLE_STROKE = 'var(--color-accent-tertiary, #0d7377)'
/** Handle size in world units; divided by zoom so ~8px on screen */
export const HANDLE_SIZE_WORLD = 8

/** Rotation handle size in world units (matches frontend rotation-handler-size 20) */
export const ROTATION_HANDLE_SIZE_WORLD = 20

/** Cursor for the rotation hit area (fallback when custom SVG not used) */
export const ROTATION_CURSOR = 'grab'

/** Base angle in degrees for each handle (horizontal double-arrow = 0°); matches frontend resize-h. */
const HANDLE_BASE_ANGLE: Record<ResizeHandlePosition, number> = {
  right: 0,
  'bottom-right': 45,
  bottom: 90,
  'bottom-left': 135,
  left: 180,
  'top-left': 225,
  top: 270,
  'top-right': 315,
}

/** Inner content of resize cursor SVG (horizontal double-arrow, white outline + black fill). */
const RESIZE_CURSOR_CONTENT =
  '<path d="m1 8 10-6v12zm30 0L21 2v12z" fill="#fff" stroke="#fff" stroke-width="2.5" stroke-linejoin="round"/><path fill="#111" d="m1 8 10-6v12zm30 0L21 2v12z"/>'

/** Rotation center of the arrow (center of original 34×16 content). */
const VIEWBOX_CENTER_X = 16
const VIEWBOX_CENTER_Y = 8

/** Square cursor size large enough to fit the arrow at any rotation (diagonal ~36px). */
const CURSOR_SIZE = 36
const CURSOR_HOTSPOT = CURSOR_SIZE / 2

/** ViewBox: square centered on rotation center so rotated content is never clipped. */
const CURSOR_VIEWBOX_MIN_X = VIEWBOX_CENTER_X - CURSOR_SIZE / 2
const CURSOR_VIEWBOX_MIN_Y = VIEWBOX_CENTER_Y - CURSOR_SIZE / 2

function buildRotatedResizeCursorDataUrl(angleDeg: number): string {
  const rotated = `<g transform="rotate(${angleDeg} ${VIEWBOX_CENTER_X} ${VIEWBOX_CENTER_Y})">${RESIZE_CURSOR_CONTENT}</g>`
  const viewBox = `${CURSOR_VIEWBOX_MIN_X} ${CURSOR_VIEWBOX_MIN_Y} ${CURSOR_SIZE} ${CURSOR_SIZE}`
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" width="${CURSOR_SIZE}" height="${CURSOR_SIZE}">${rotated}</svg>`
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}") ${CURSOR_HOTSPOT} ${CURSOR_HOTSPOT}, auto`
}

/**
 * Resize cursor for the given handle. Always returns a data URL of the
 * horizontal double-arrow SVG rotated by the handle's effective angle.
 */
export function getResizeCursor(
  position: ResizeHandlePosition,
  rotationDeg?: number
): string {
  const baseAngle = HANDLE_BASE_ANGLE[position] ?? 135
  const effectiveAngle = ((baseAngle + (rotationDeg ?? 0)) % 360 + 360) % 360
  return buildRotatedResizeCursorDataUrl(effectiveAngle)
}
