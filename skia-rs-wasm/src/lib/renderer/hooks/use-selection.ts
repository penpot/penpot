/**
 * React hook for selection
 * Manages selection subscriptions
 */

import { useEffect } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { handleAreaSelection } from '../handlers/selection'

export function useSelection() {
  const isSelecting = useWorkspaceStore(state => state.isSelecting)
  const areaSelectionAppend = useWorkspaceStore(state => state.areaSelectionAppend)
  const areaSelectionRemove = useWorkspaceStore(state => state.areaSelectionRemove)
  const selectedIds = useWorkspaceStore(state => state.selectedIds)
  const selectionRect = useWorkspaceStore(state => state.selectionRect)

  useEffect(() => {
    if (!isSelecting) return

    const subscription = handleAreaSelection(
      areaSelectionAppend,
      areaSelectionRemove
    ).subscribe()
    return () => subscription.unsubscribe()
  }, [isSelecting, areaSelectionAppend, areaSelectionRemove])
  
  return { selectedIds, selectionRect }
}


