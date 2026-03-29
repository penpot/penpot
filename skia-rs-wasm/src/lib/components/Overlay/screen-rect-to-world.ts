import type { ViewportData } from '../../renderer/viewport'

export interface ScreenRectLike {
  x?: number
  y?: number
  width?: number
  height?: number
}

export function screenRectToWorld(vp: ViewportData, rect: ScreenRectLike) {
  return {
    x: vp.panX + (rect.x ?? 0) / vp.zoom,
    y: vp.panY + (rect.y ?? 0) / vp.zoom,
    width: (rect.width ?? 0) / vp.zoom,
    height: (rect.height ?? 0) / vp.zoom,
  }
}
