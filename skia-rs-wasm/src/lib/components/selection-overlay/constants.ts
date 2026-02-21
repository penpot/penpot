import type { ResizeHandlePosition } from '../../renderer/types'

export const SELECTION_STROKE = 'var(--color-accent-tertiary, #0d7377)'
export const SELECTION_STROKE_WIDTH = 1
export const HANDLE_FILL = 'var(--app-white, #fff)'
export const HANDLE_STROKE = 'var(--color-accent-tertiary, #0d7377)'
/** Handle size in world units; divided by zoom so ~8px on screen */
export const HANDLE_SIZE_WORLD = 8

export function getHandleCursor(position: ResizeHandlePosition): string {
  switch (position) {
    case 'top-left':
    case 'bottom-right':
      return 'nwse-resize'
    case 'top-right':
    case 'bottom-left':
      return 'nesw-resize'
    case 'top':
    case 'bottom':
      return 'ns-resize'
    case 'left':
    case 'right':
      return 'ew-resize'
    default:
      return 'nwse-resize'
  }
}
