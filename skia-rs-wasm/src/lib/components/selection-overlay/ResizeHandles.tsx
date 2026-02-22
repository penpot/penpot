import { useMemo } from 'react'
import type { ResizeHandlePosition } from '../../renderer/types'
import { HANDLE_FILL, HANDLE_STROKE, HANDLE_SIZE_WORLD, getResizeCursor } from './constants'

export interface ResizeHandlesProps {
  effectiveBounds: { x: number; y: number; width: number; height: number }
  zoom: number
  /** When set, resize cursor is rotated by this angle so it aligns with the handle. */
  rotationDeg?: number
  onResizeHandlePointerDown: (e: React.PointerEvent, position: ResizeHandlePosition) => void
}

export function ResizeHandles({
  effectiveBounds,
  zoom,
  rotationDeg,
  onResizeHandlePointerDown,
}: ResizeHandlesProps) {
  const handleHalf = (HANDLE_SIZE_WORLD / zoom) / 2
  const hitSize = handleHalf * 2

  const edgeHandleRects = useMemo(() => {
    const { x, y, width, height } = effectiveBounds
    return [
      { position: 'top' as const, x, y, width, height: hitSize },
      { position: 'bottom' as const, x, y: y + height - hitSize, width, height: hitSize },
      { position: 'left' as const, x, y, width: hitSize, height },
      { position: 'right' as const, x: x + width - hitSize, y, width: hitSize, height },
    ]
  }, [effectiveBounds, hitSize])

  const cornerHandleRects = useMemo(() => {
    const { x, y, width, height } = effectiveBounds
    return [
      { position: 'top-left' as const, cx: x, cy: y },
      { position: 'top-right' as const, cx: x + width, cy: y },
      { position: 'bottom-right' as const, cx: x + width, cy: y + height },
      { position: 'bottom-left' as const, cx: x, cy: y + height },
    ]
  }, [effectiveBounds])

  return (
    <>
      {edgeHandleRects.map(({ position, x, y, width, height }) => (
        <rect
          key={`edge-${position}`}
          x={x}
          y={y}
          width={width}
          height={height}
          fill="transparent"
          style={{
            pointerEvents: 'auto',
            cursor: getResizeCursor(position, rotationDeg),
          }}
          onPointerDown={(e) => onResizeHandlePointerDown(e, position)}
        />
      ))}
      {cornerHandleRects.map(({ position, cx, cy }) => (
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
            cursor: getResizeCursor(position, rotationDeg),
          }}
          onPointerDown={(e) => onResizeHandlePointerDown(e, position)}
        />
      ))}
    </>
  )
}
