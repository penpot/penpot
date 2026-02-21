/**
 * React hook for resizing shapes
 * Subscribes to resize stream when user has started a resize from a handle
 */

import { useEffect } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { startResizeSelected } from '../handlers/resize'
import { mousePosition$ } from '../streams'

export function useResize() {
  const isResizing = useWorkspaceStore((state) => state.isResizing)
  const resizeHandle = useWorkspaceStore((state) => state.resizeHandle)

  useEffect(() => {
    const initialPos = mousePosition$.value
    if (!isResizing || !resizeHandle) return

    if (!initialPos) return

    const subscription = startResizeSelected(initialPos, resizeHandle).subscribe()
    return () => subscription.unsubscribe()
  }, [isResizing, resizeHandle])
}
