import { useMemo } from 'react'

export interface MoveHitAreaProps {
  bounds: { x: number; y: number; width: number; height: number }
  hitSize: number
  /** When set (e.g. during resize/rotation drag), use this cursor so it stays consistent. */
  overrideCursor?: string | null
  onPointerDown: (e: React.PointerEvent) => void
}

export function MoveHitArea({ bounds, hitSize, overrideCursor, onPointerDown }: MoveHitAreaProps) {
  const innerRect = useMemo(() => {
    const { x, y, width, height } = bounds
    const inset = hitSize
    const w = width - 2 * inset
    const h = height - 2 * inset
    if (w <= 0 || h <= 0) return null
    return { x: x + inset, y: y + inset, width: w, height: h }
  }, [bounds, hitSize])

  if (!innerRect) return null

  return (
    <rect
      x={innerRect.x}
      y={innerRect.y}
      width={innerRect.width}
      height={innerRect.height}
      fill="transparent"
      style={{ pointerEvents: 'auto', cursor: overrideCursor ?? 'default' }}
      onPointerDown={onPointerDown}
    />
  )
}
