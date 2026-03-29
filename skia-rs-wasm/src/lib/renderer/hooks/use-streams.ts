/**
 * Hook to set up event listeners and update pointer / modifier signals
 */

import type { RefObject } from 'react'
import { useEffect } from 'react'
import {
  keyboardSpace,
  modAlt,
  modCtrl,
  modMeta,
  modShift,
  pointerPos,
} from '../signals/pointer'

export function useStreams(canvasRef: RefObject<HTMLCanvasElement | null>) {
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const pushPosition = (clientX: number, clientY: number, shiftKey: boolean, altKey: boolean, ctrlKey: boolean, metaKey: boolean) => {
      const rect = canvas.getBoundingClientRect()
      pointerPos.value = { x: clientX - rect.left, y: clientY - rect.top }
      modShift.value = shiftKey
      modAlt.value = altKey
      modCtrl.value = ctrlKey
      modMeta.value = metaKey
    }
    const handlePointerMove = (e: PointerEvent) => {
      pushPosition(e.clientX, e.clientY, e.shiftKey, e.altKey, e.ctrlKey, e.metaKey)
    }

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space') keyboardSpace.value = true
    }

    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space') keyboardSpace.value = false
    }

    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('keyup', handleKeyUp)

    return () => {
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('keyup', handleKeyUp)
    }
  }, [canvasRef])
}
