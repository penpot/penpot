/**
 * Four corner resize squares drawn in world space (no selection transform).
 * Matches frontend: only corner handles stay axis-aligned when selection has shear.
 */
import type { ResizeHandlePosition } from '../../renderer/types'
import { HANDLE_FILL, HANDLE_STROKE, HANDLE_SIZE_WORLD, getResizeCursor } from './constants'
import type { SelectionWorldCorners } from './world-corners'

export interface CornerHandlesProps {
  worldCorners: SelectionWorldCorners
  zoom: number
  rotationDeg?: number
  halfFlip?: boolean
  overrideCursor?: string | null
  onResizeHandlePointerDown: (e: React.PointerEvent, position: ResizeHandlePosition) => void
}

export function CornerHandles({
  worldCorners,
  zoom,
  rotationDeg,
  halfFlip,
  overrideCursor,
  onResizeHandlePointerDown,
}: CornerHandlesProps) {
  const handleHalf = (HANDLE_SIZE_WORLD / zoom) / 2
  const corners = [
    { position: 'top-left' as const, cx: worldCorners.topLeft.x, cy: worldCorners.topLeft.y },
    { position: 'top-right' as const, cx: worldCorners.topRight.x, cy: worldCorners.topRight.y },
    { position: 'bottom-right' as const, cx: worldCorners.bottomRight.x, cy: worldCorners.bottomRight.y },
    { position: 'bottom-left' as const, cx: worldCorners.bottomLeft.x, cy: worldCorners.bottomLeft.y },
  ]
  return (
    <>
      {corners.map(({ position, cx, cy }) => (
        <rect
          key={`corner-${position}`}
          x={cx - handleHalf}
          y={cy - handleHalf}
          width={handleHalf * 2}
          height={handleHalf * 2}
          fill={HANDLE_FILL}
          stroke={HANDLE_STROKE}
          strokeWidth={1 / zoom}
          style={{
            pointerEvents: 'auto',
            cursor: overrideCursor ?? getResizeCursor(position, rotationDeg, halfFlip),
          }}
          onPointerDown={(e) => onResizeHandlePointerDown(e, position)}
        />
      ))}
    </>
  )
}
