/**
 * Hook to set up event listeners and update RxJS streams
 */

import type { RefObject } from 'react'
import { useEffect } from 'react'
import { mousePosition$, mousePositionShift$, mousePositionAlt$, mousePositionMod$, keyboardSpace$ } from '../streams'

export function useStreams(canvasRef: RefObject<HTMLCanvasElement | null>) {
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    
    const pushPosition = (clientX: number, clientY: number, shiftKey: boolean, altKey: boolean, ctrlOrMeta: boolean) => {
      const rect = canvas.getBoundingClientRect()
      mousePosition$.next({ x: clientX - rect.left, y: clientY - rect.top })
      mousePositionShift$.next(shiftKey)
      mousePositionAlt$.next(altKey)
      mousePositionMod$.next(ctrlOrMeta)
    }
    const handleMouseMove = (e: MouseEvent) => {
      pushPosition(e.clientX, e.clientY, e.shiftKey, e.altKey, e.ctrlKey || e.metaKey)
    }
    const handlePointerMove = (e: PointerEvent) => {
      pushPosition(e.clientX, e.clientY, e.shiftKey, e.altKey, e.ctrlKey || e.metaKey)
    }
    
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space') keyboardSpace$.next(true)
    }
    
    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space') keyboardSpace$.next(false)
    }
    
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('keyup', handleKeyUp)
    
    return () => {
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('keyup', handleKeyUp)
    }
  }, [canvasRef])
}


