import { SELECTION_STROKE_WIDTH } from './constants'

export interface AreaMarqueeProps {
  world: { x: number; y: number; width: number; height: number }
  zoom: number
}

export function AreaMarquee({ world, zoom }: AreaMarqueeProps) {
  return (
    <rect
      x={world.x}
      y={world.y}
      width={world.width}
      height={world.height}
      fill="rgba(37,99,235,0.1)"
      stroke="#2563eb"
      strokeWidth={SELECTION_STROKE_WIDTH / zoom}
    />
  )
}
