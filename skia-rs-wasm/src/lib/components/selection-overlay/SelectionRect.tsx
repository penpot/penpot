import type { Rect } from '../../renderer/selection-bounds'
import { SELECTION_STROKE, SELECTION_STROKE_WIDTH, SELECTION_STROKE_WIDTH_MAX } from './constants'

export interface SelectionRectProps {
  bounds: Rect
  /** When set, the rect is drawn rotated around center so the outline matches the shape. */
  rotation?: number
  center?: { x: number; y: number }
  /** When true, do not apply rotation transform (parent group applies it). */
  skipTransform?: boolean
  /** When set, stroke scales with zoom (world units) so it does not dominate when zoomed out. */
  zoom?: number
}

export function SelectionRect({ bounds, rotation, center, skipTransform, zoom }: SelectionRectProps) {
  const strokeWidth =
    zoom != null
      ? Math.min(SELECTION_STROKE_WIDTH / zoom, SELECTION_STROKE_WIDTH_MAX)
      : SELECTION_STROKE_WIDTH
  const rect = (
    <rect
      x={bounds.x}
      y={bounds.y}
      width={bounds.width}
      height={bounds.height}
      fill="none"
      stroke={SELECTION_STROKE}
      strokeWidth={strokeWidth}
      {...(zoom == null ? { vectorEffect: 'non-scaling-stroke' as const } : {})}
      style={{ pointerEvents: 'none' }}
    />
  )
  if (!skipTransform && rotation != null && rotation !== 0 && center) {
    return (
      <g transform={`rotate(${rotation} ${center.x} ${center.y})`}>
        {rect}
      </g>
    )
  }
  return rect
}
