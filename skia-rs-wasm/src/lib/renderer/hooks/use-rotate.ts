/**
 * React hook for rotating shapes
 * Subscribes to rotate stream when user has started a rotation from the rotation hit area
 */

import { useEffect } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { startRotateSelected } from '../handlers/rotate'
import { mousePosition$ } from '../streams'

export function useRotate() {
  const isRotating = useWorkspaceStore((state) => state.isRotating)

  useEffect(() => {
    if (!isRotating) return

    const initialPos = mousePosition$.value
    if (!initialPos) return

    const subscription = startRotateSelected(initialPos).subscribe()
    return () => subscription.unsubscribe()
  }, [isRotating])
}
