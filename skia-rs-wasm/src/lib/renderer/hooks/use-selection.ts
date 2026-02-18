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

    // #region agent log
    fetch('http://127.0.0.1:7244/ingest/f0136137-81f1-4f6e-a7b5-217ac99b12a5',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({location:'use-selection.ts:useEffect',message:'subscribing to handleAreaSelection',data:{isSelecting},timestamp:Date.now(),hypothesisId:'H4'})}).catch(()=>{});
    // #endregion

    const subscription = handleAreaSelection(
      areaSelectionAppend,
      areaSelectionRemove
    ).subscribe()
    return () => subscription.unsubscribe()
  }, [isSelecting, areaSelectionAppend, areaSelectionRemove])
  
  return { selectedIds, selectionRect }
}


