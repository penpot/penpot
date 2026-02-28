import type { Matrix } from 'penpot-exporter'
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

const ROTATION_CURSOR_CONTENT =
  '<path d="M12.75.438h9v9c-.25-.125-.406-.269-.6-.463l-.106-.106-.338-.338-.237-.237-.613-.619-.625-.625L18 5.813c-.281.237-.556.481-.813.744l-.106.106-.344.344-.119.119-.625.625-.644.637-.5.5-.237.237c-1.925 1.906-3.156 4.438-3.188 7.188.003 2.438.787 5.037 2.513 6.831q.119.119.237.231c.119.119.225.237.331.362.275.319.575.613.875.912l.181.181.469.463.475.475.933.92a12 12 0 0 0 .831-.762l.113-.113.237-.237.375-.375q.525-.531 1.056-1.063l.65-.65q.125-.125.244-.25l.344-.344.1-.106c.225-.225.225-.225.362-.225v9h-9c.119-.237.219-.362.406-.55l.087-.087.281-.281.2-.194q.256-.256.519-.512.263-.263.531-.525.519-.525 1.038-1.038a1.5 1.5 0 0 0-.356-.487 7 7 0 0 1-.463-.487c-.25-.287-.525-.556-.794-.825q-.163-.156-.319-.319l-.5-.487c-.631-.625-1.238-1.238-1.756-1.956l-.075-.106c-1.369-1.875-2.3-4.225-2.313-6.563l-.001-.15-.002-.481v-.166c.001-.794.021-1.563.203-2.344l.034-.15c.5-2.15 1.488-4.138 3.013-5.75l.125-.138c.662-.694 1.35-1.369 2.031-2.05l.569-.569q.548-.548 1.105-1.098c-.1-.244-.269-.412-.456-.594l-.1-.1-.219-.219q-.175-.169-.344-.344c-.244-.244-.487-.481-.731-.725l-.85-.844-.344-.338-.206-.206-.094-.094a3 3 0 0 1-.219-.225z" fill="#111" stroke="#fff" stroke-width=".25" stroke-linejoin="round" stroke-linecap="round"/>'

  /** Rotation center of the arrow (center of original 34×16 content). */
const VIEWBOX_CENTER_X = 16
const VIEWBOX_CENTER_Y = 8

/** Square cursor size; 32px max for browser compatibility (Chrome/FF limit custom cursor to 32×32). */
const CURSOR_SIZE = 32
const CURSOR_HOTSPOT = CURSOR_SIZE / 2

/** ViewBox: square centered on rotation center so rotated content is never clipped. */
const CURSOR_VIEWBOX_MIN_X = VIEWBOX_CENTER_X - CURSOR_SIZE / 2
const CURSOR_VIEWBOX_MIN_Y = VIEWBOX_CENTER_Y - CURSOR_SIZE / 2

const ROTATION_VIEWBOX_SIZE = 32
const ROTATION_CENTER_X = 16
const ROTATION_CENTER_Y = 16

/** Offset so the rotation icon’s “forward” direction aligns with the handle (icon may point up at 0°). */
const ROTATION_CURSOR_ANGLE_OFFSET_DEG = 180

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

function buildRotatedRotationCursorDataUrl(angleDeg: number): string {
  const viewBox = `0 0 ${ROTATION_VIEWBOX_SIZE} ${ROTATION_VIEWBOX_SIZE}`
  const rotated = `<g transform="rotate(${angleDeg} ${ROTATION_CENTER_X} ${ROTATION_CENTER_Y})">${ROTATION_CURSOR_CONTENT}</g>`
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" width="${CURSOR_SIZE}" height="${CURSOR_SIZE}">${rotated}</svg>`
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}") ${CURSOR_HOTSPOT} ${CURSOR_HOTSPOT}, ${ROTATION_CURSOR}`
}

/** Precomputed rotation cursors at 5° steps (0°, 5°, …, 355°) for O(1) lookup. */
const ROTATION_CURSOR_STEP_DEG = 5
const ROTATION_CURSOR_CACHE: readonly string[] = (() => {
  const n = 360 / ROTATION_CURSOR_STEP_DEG
  const cache: string[] = []
  for (let i = 0; i < n; i++) cache.push(buildRotatedRotationCursorDataUrl(i * ROTATION_CURSOR_STEP_DEG))
  return cache
})()

function rotationCursorIndexForAngle(angleDeg: number): number {
  const normalized = (angleDeg % 360 + 360) % 360
  return Math.round(normalized / ROTATION_CURSOR_STEP_DEG) % (360 / ROTATION_CURSOR_STEP_DEG)
}

/**
 * Rotation cursor for the given corner. Returns the rotation cursor SVG
 * rotated so it points toward the handle (base angle + selection rotation + offset for icon’s default direction).
 */
export function getRotationCursor(
  position: ResizeHandlePosition,
  rotationDeg?: number
): string {
  const baseAngle = HANDLE_BASE_ANGLE[position] ?? 135
  const effectiveAngle =
    (baseAngle + (rotationDeg ?? 0) + ROTATION_CURSOR_ANGLE_OFFSET_DEG + 360) % 360
  return ROTATION_CURSOR_CACHE[rotationCursorIndexForAngle(effectiveAngle)]
}