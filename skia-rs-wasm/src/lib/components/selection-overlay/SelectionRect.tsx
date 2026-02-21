import type { Rect } from '../../renderer/selection-bounds'
import { SELECTION_STROKE, SELECTION_STROKE_WIDTH } from './constants'

export interface SelectionRectProps {
  bounds: Rect
  zoom: number
}

export function SelectionRect({ bounds, zoom }: SelectionRectProps) {
  return (
    <rect
      x={bounds.x}
      y={bounds.y}
      width={bounds.width}
      height={bounds.height}
      fill="none"
      stroke={SELECTION_STROKE}
      strokeWidth={SELECTION_STROKE_WIDTH / zoom}
      style={{ pointerEvents: 'none' }}
    />
  )
}
