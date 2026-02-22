import type { Rect } from '../../renderer/selection-bounds'
import { SELECTION_STROKE, SELECTION_STROKE_WIDTH } from './constants'

export interface SelectionRectProps {
  bounds: Rect
  /** When set, the rect is drawn rotated around center so the outline matches the shape. */
  rotation?: number
  center?: { x: number; y: number }
  /** When true, do not apply rotation transform (parent group applies it). */
  skipTransform?: boolean
}

export function SelectionRect({ bounds, rotation, center, skipTransform }: SelectionRectProps) {
  const rect = (
    <rect
      x={bounds.x}
      y={bounds.y}
      width={bounds.width}
      height={bounds.height}
      fill="none"
      stroke={SELECTION_STROKE}
      strokeWidth={SELECTION_STROKE_WIDTH}
      vectorEffect="non-scaling-stroke"
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
