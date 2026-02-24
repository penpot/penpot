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
  const setIsRotating = useWorkspaceStore((state) => state.setIsRotating)
  const setRotationCorner = useWorkspaceStore((state) => state.setRotationCorner)

  useEffect(() => {
    if (!isRotating) return

    const initialPos = mousePosition$.value
    if (!initialPos) return

    const subscription = startRotateSelected(initialPos).subscribe()
    // If the stream was EMPTY (e.g. group selected), subscription completes immediately
    // and nothing in the handler ever clears isRotating — clear it here so rotation works again.
    if (subscription.closed) {
      setRotationCorner(null)
      setIsRotating(false)
    }
    return () => subscription.unsubscribe()
  }, [isRotating, setIsRotating, setRotationCorner])
}
