/**
 * React hook for selection
 * Manages selection subscriptions
 */

import { useEffect } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { handleAreaSelection } from '../handlers/selection'

export function useSelection() {
  const isSelecting = useWorkspaceStore(state => state.isSelecting)
  const selectedIds = useWorkspaceStore(state => state.selectedIds)
  const selectionRect = useWorkspaceStore(state => state.selectionRect)
  
  useEffect(() => {
    if (!isSelecting) return
    
    const subscription = handleAreaSelection().subscribe()
    return () => subscription.unsubscribe()
  }, [isSelecting])
  
  return { selectedIds, selectionRect }
}


