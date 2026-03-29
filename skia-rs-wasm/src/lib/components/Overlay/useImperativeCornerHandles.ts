import type { RefObject } from 'react'
import { useEffect, useLayoutEffect } from 'react'
import { effect } from '@preact/signals-core'
import { viewport as viewportSignal } from '../../renderer/signals/pointer'
import {
  selectionCornerHandlesVisible,
  wasmSelectionRect as wasmSelectionRectSignal,
} from '../../renderer/signals/selection'
import type { ResizeHandlePosition } from '../../renderer/types'
import {
  HANDLE_SIZE_WORLD,
  MIN_SELRECT_SIDE_SCREEN,
  getResizeCursor,
  matrixHasHalfFlip,
  matrixToRotationDeg,
} from './constants'
import { finiteSelectionOverlayRect } from './finite-selection-overlay-rect'
import { getSelectionWorldCorners } from './world-corners'

export const CORNER_HANDLE_POSITIONS: readonly [
  ResizeHandlePosition,
  ResizeHandlePosition,
  ResizeHandlePosition,
  ResizeHandlePosition,
] = ['top-left', 'top-right', 'bottom-right', 'bottom-left']

export type CornerRectRefsTuple = [
  SVGRectElement | null,
  SVGRectElement | null,
  SVGRectElement | null,
  SVGRectElement | null,
]

export interface CornerPointerRef {
  canvasRef: RefObject<HTMLCanvasElement | null>
  sendCornerDown: (position: ResizeHandlePosition, screenPos: { x: number; y: number }) => void
}

export function useImperativeCornerHandles(
  cornerHandlesGRef: RefObject<SVGGElement | null>,
  cornerRectRefs: RefObject<CornerRectRefsTuple>,
  cornerOverrideCursorRef: RefObject<string | null>,
  cornerPointerRef: RefObject<CornerPointerRef | null>
): void {
  useLayoutEffect(() => {
    return effect(() => {
      void selectionCornerHandlesVisible.value
      const visible = selectionCornerHandlesVisible.value
      const sel = wasmSelectionRectSignal.value
      const vp = viewportSignal.value
      const g = cornerHandlesGRef.current
      const rects = cornerRectRefs.current
      if (!g || !rects[0] || !rects[1] || !rects[2] || !rects[3]) return

      if (
        !visible ||
        !finiteSelectionOverlayRect(sel) ||
        !vp ||
        !Number.isFinite(vp.zoom) ||
        vp.zoom <= 0
      ) {
        g.style.display = 'none'
        return
      }

      const zoom = vp.zoom
      if (Math.min(sel.width, sel.height) * zoom < MIN_SELRECT_SIDE_SCREEN) {
        g.style.display = 'none'
        return
      }

      const corners = getSelectionWorldCorners(sel)
      const handleHalf = (HANDLE_SIZE_WORLD / zoom) / 2
      const size = handleHalf * 2
      const strokeW = 1 / zoom
      const pts = [corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft] as const
      const rd = matrixToRotationDeg(sel.transform)
      const hf = matrixHasHalfFlip(sel.transform)
      const oc = cornerOverrideCursorRef.current

      g.style.display = ''
      for (let i = 0; i < 4; i++) {
        const p = pts[i]
        const el = rects[i]!
        el.setAttribute('x', String(p.x - handleHalf))
        el.setAttribute('y', String(p.y - handleHalf))
        el.setAttribute('width', String(size))
        el.setAttribute('height', String(size))
        el.setAttribute('stroke-width', String(strokeW))
        el.style.cursor = oc ?? getResizeCursor(CORNER_HANDLE_POSITIONS[i], rd, hf)
      }
    })
  }, [cornerHandlesGRef, cornerRectRefs, cornerOverrideCursorRef])

  useEffect(() => {
    let disposed = false
    let raf = 0
    const removers: (() => void)[] = []

    const tryAttach = () => {
      if (disposed) return
      const rects = cornerRectRefs.current
      if (!rects[0] || !rects[1] || !rects[2] || !rects[3]) {
        raf = requestAnimationFrame(tryAttach)
        return
      }
      for (let i = 0; i < 4; i++) {
        const el = rects[i]!
        const position = CORNER_HANDLE_POSITIONS[i]
        const down = (e: PointerEvent) => {
          if (e.button !== 0) return
          e.preventDefault()
          e.stopPropagation()
          const ptr = cornerPointerRef.current
          if (!ptr) return
          const canvas = ptr.canvasRef.current
          if (!canvas) return
          const br = canvas.getBoundingClientRect()
          const screenPos = { x: e.clientX - br.left, y: e.clientY - br.top }
          ptr.sendCornerDown(position, screenPos)
          if (e.currentTarget instanceof SVGElement) e.currentTarget.setPointerCapture(e.pointerId)
        }
        el.addEventListener('pointerdown', down)
        removers.push(() => el.removeEventListener('pointerdown', down))
      }
    }

    tryAttach()
    return () => {
      disposed = true
      cancelAnimationFrame(raf)
      removers.forEach((r) => r())
    }
  }, [cornerRectRefs, cornerPointerRef])
}
