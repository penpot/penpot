/**
 * React hook for selection
 * Manages selection subscriptions
 */

import { useEffect, useMemo } from 'react'
import { useSnapshot } from 'valtio'
import { docProxy } from '../store/doc-proxy'
import { useWorkspaceStore } from '../store/workspace-store'
import { handleAreaSelection } from '../handlers/selection'

export function useSelection() {
  const doc = useSnapshot(docProxy)
  const selectedIds = useMemo(() => new Set(doc.selectedIds), [doc.selectedIds])
  const isSelecting = useWorkspaceStore(state => state.isSelecting)
  const areaSelectionAppend = useWorkspaceStore(state => state.areaSelectionAppend)
  const areaSelectionRemove = useWorkspaceStore(state => state.areaSelectionRemove)
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


