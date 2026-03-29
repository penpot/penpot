/**
 * Rotation hit areas at the four corners of the selection (matches frontend
 * viewport/selection.cljs: four :rotation handlers at top-left, top-right,
 * bottom-right, bottom-left; each a rect outside the corner with rotate cursor).
 */
import { useMemo } from 'react'
import type { ResizeHandlePosition } from '../../renderer/types'
import { ROTATION_HANDLE_SIZE_WORLD, getRotationCursor } from './constants'

export interface RotationHitAreaProps {
  bounds: { x: number; y: number; width: number; height: number }
  zoom: number
  rotationDeg?: number
  halfFlip?: boolean
  overrideCursor?: string | null
  onPointerDown: (e: React.PointerEvent, position: ResizeHandlePosition) => void
}

export function RotationHitArea({
  bounds,
  zoom,
  rotationDeg,
  halfFlip,
  overrideCursor,
  onPointerDown,
}: RotationHitAreaProps) {
  const size = ROTATION_HANDLE_SIZE_WORLD / zoom

  const handleRects = useMemo(() => {
    const { x, y, width, height } = bounds
    const corners: Array<{ position: ResizeHandlePosition; cx: number; cy: number; dx: number; dy: number }> = [
      { position: 'top-left', cx: x, cy: y, dx: size, dy: size },
      { position: 'top-right', cx: x + width, cy: y, dx: 0, dy: size },
      { position: 'bottom-right', cx: x + width, cy: y + height, dx: 0, dy: 0 },
      { position: 'bottom-left', cx: x, cy: y + height, dx: size, dy: 0 },
    ]
    return corners.map(({ position, cx, cy, dx, dy }) => ({
      key: position,
      position,
      x: cx - dx,
      y: cy - dy,
      width: size,
      height: size,
    }))
  }, [bounds, size])

  return (
    <>
      {handleRects.map(({ key, position, x, y, width, height }) => (
        <rect
          key={key}
          x={x}
          y={y}
          width={width}
          height={height}
          fill="transparent"
          style={{
            pointerEvents: 'auto',
            cursor: overrideCursor ?? getRotationCursor(position, rotationDeg ?? 0, halfFlip),
          }}
          onPointerDown={(e) => onPointerDown(e, position)}
        />
      ))}
    </>
  )
}
