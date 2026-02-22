/**
 * Rotation hit areas at the four corners of the selection (matches frontend
 * viewport/selection.cljs: four :rotation handlers at top-left, top-right,
 * bottom-right, bottom-left; each a rect outside the corner with rotate cursor).
 */
import { useMemo } from 'react'
import { ROTATION_CURSOR, ROTATION_HANDLE_SIZE_WORLD } from './constants'

export interface RotationHitAreaProps {
  bounds: { x: number; y: number; width: number; height: number }
  zoom: number
  onPointerDown: (e: React.PointerEvent) => void
}

export function RotationHitArea({ bounds, zoom, onPointerDown }: RotationHitAreaProps) {
  const size = ROTATION_HANDLE_SIZE_WORLD / zoom

  const handleRects = useMemo(() => {
    const { x, y, width, height } = bounds
    const corners: Array<{ position: string; cx: number; cy: number; dx: number; dy: number }> = [
      { position: 'top-left', cx: x, cy: y, dx: size, dy: size },
      { position: 'top-right', cx: x + width, cy: y, dx: 0, dy: size },
      { position: 'bottom-right', cx: x + width, cy: y + height, dx: 0, dy: 0 },
      { position: 'bottom-left', cx: x, cy: y + height, dx: size, dy: 0 },
    ]
    return corners.map(({ position, cx, cy, dx, dy }) => ({
      key: position,
      x: cx - dx,
      y: cy - dy,
      width: size,
      height: size,
    }))
  }, [bounds, size])

  return (
    <>
      {handleRects.map(({ key, x, y, width, height }) => (
        <rect
          key={key}
          x={x}
          y={y}
          width={width}
          height={height}
          fill="transparent"
          style={{ pointerEvents: 'auto', cursor: ROTATION_CURSOR }}
          onPointerDown={onPointerDown}
        />
      ))}
    </>
  )
}
