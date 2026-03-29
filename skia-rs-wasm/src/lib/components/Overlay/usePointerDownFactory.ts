import type { RefObject } from 'react'
import { useMemo } from 'react'
import { pointerPos } from '../../renderer/signals/pointer'
import type { CanvasActorRef } from '../../renderer/machine/canvas-actor-context'
import type { ResizeHandlePosition } from '../../renderer/types'

function screenPositionFromCanvas(
  canvasRef: RefObject<HTMLCanvasElement | null>,
  e: React.PointerEvent
): { x: number; y: number } | null {
  const canvas = canvasRef.current
  if (!canvas) return null
  const rect = canvas.getBoundingClientRect()
  return {
    x: e.clientX - rect.left,
    y: e.clientY - rect.top,
  }
}

export function usePointerDownFactory(
  canvasRef: RefObject<HTMLCanvasElement | null>,
  canvasActor: CanvasActorRef
) {
  return useMemo(
    () => ({
      onSelectionRectPointerDown(e: React.PointerEvent) {
        if (e.button !== 0) return
        e.preventDefault()
        const pos = screenPositionFromCanvas(canvasRef, e)
        if (pos) {
          pointerPos.value = pos
          canvasActor.send({ type: 'POINTER_DOWN_ON_SELECTION', position: pos })
        }
        const target = e.currentTarget
        if (target instanceof Element) target.setPointerCapture(e.pointerId)
      },
      onResizeHandlePointerDown(e: React.PointerEvent, position: ResizeHandlePosition) {
        if (e.button !== 0) return
        e.preventDefault()
        e.stopPropagation()
        const pos = screenPositionFromCanvas(canvasRef, e)
        if (pos) {
          pointerPos.value = pos
          canvasActor.send({ type: 'POINTER_DOWN_ON_CORNER', handle: position, position: pos })
        }
        const target = e.currentTarget
        if (target instanceof Element) target.setPointerCapture(e.pointerId)
      },
      onRotationPointerDown(e: React.PointerEvent, position: ResizeHandlePosition) {
        if (e.button !== 0) return
        e.preventDefault()
        e.stopPropagation()
        const pos = screenPositionFromCanvas(canvasRef, e)
        if (pos) {
          pointerPos.value = pos
          canvasActor.send({ type: 'POINTER_DOWN_ON_ROTATION', corner: position, position: pos })
        }
        const target = e.currentTarget
        if (target instanceof Element) target.setPointerCapture(e.pointerId)
      },
    }),
    [canvasRef, canvasActor]
  )
}
