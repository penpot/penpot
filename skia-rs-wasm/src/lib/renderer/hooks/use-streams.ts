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
    
    const handleMouseMove = (e: MouseEvent) => {
      const rect = canvas.getBoundingClientRect()
      mousePosition$.next({
        x: e.clientX - rect.left,
        y: e.clientY - rect.top
      })
      mousePositionShift$.next(e.shiftKey)
      mousePositionAlt$.next(e.altKey)
      mousePositionMod$.next(e.ctrlKey || e.metaKey)
    }
    
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space') keyboardSpace$.next(true)
    }
    
    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space') keyboardSpace$.next(false)
    }
    
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('keyup', handleKeyUp)
    
    return () => {
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('keyup', handleKeyUp)
    }
  }, [canvasRef])
}


