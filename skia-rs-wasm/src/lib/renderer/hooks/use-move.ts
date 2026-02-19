/**
 * React hook for moving shapes
 * Manages move subscriptions
 */

import { useEffect } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { startMoveSelected } from '../handlers/move'
import { mousePosition$ } from '../streams'

export function useMove() {
  const isMoving = useWorkspaceStore(state => state.isMoving)
  
  useEffect(() => {
    console.log('[MOVE_DEBUG] useMove effect', { isMoving })
    if (!isMoving) return

    const initialPos = mousePosition$.value
    console.log('[MOVE_DEBUG] useMove initialPos', { initialPos })
    if (!initialPos) return

    console.log('[MOVE_DEBUG] useMove calling startMoveSelected')
    const subscription = startMoveSelected(initialPos).subscribe()
    return () => subscription.unsubscribe()
  }, [isMoving])
}


