/**
 * Subscribes to drag-to-draw rectangle when `isDrawingShape` is true (started from viewport mousedown).
 */

import { useEffect } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { handleDrawRect } from '../handlers/draw-shape'

export function useDrawShape() {
  const isDrawingShape = useWorkspaceStore((state) => state.isDrawingShape)

  useEffect(() => {
    if (!isDrawingShape) return
    const subscription = handleDrawRect().subscribe()
    return () => subscription.unsubscribe()
  }, [isDrawingShape])
}
