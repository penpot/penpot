import type { RefObject } from 'react'
import { useLayoutEffect } from 'react'
import { effect } from '@preact/signals-core'
import { viewport as viewportSignal } from '../../renderer/signals/pointer'
import { selectionRectOutlineVisible, wasmSelectionRect as wasmSelectionRectSignal } from '../../renderer/signals/selection'
import { SELECTION_STROKE_WIDTH, SELECTION_STROKE_WIDTH_MAX } from './constants'
import { finiteSelectionOverlayRect } from './finite-selection-overlay-rect'

export function useImperativeSelectionRect(
  hotGRef: RefObject<SVGGElement | null>,
  selRectRef: RefObject<SVGRectElement | null>
): void {
  useLayoutEffect(() => {
    return effect(() => {
      const visible = selectionRectOutlineVisible.value
      const sel = wasmSelectionRectSignal.value
      const vp = viewportSignal.value
      const g = hotGRef.current
      const r = selRectRef.current
      if (!g || !r) return
      if (!visible || !finiteSelectionOverlayRect(sel) || !vp || !Number.isFinite(vp.zoom) || vp.zoom <= 0) {
        g.style.display = 'none'
        return
      }
      const zoom = vp.zoom
      const hw = sel.width / 2
      const hh = sel.height / 2
      const strokeWidth = Math.min(SELECTION_STROKE_WIDTH / zoom, SELECTION_STROKE_WIDTH_MAX)
      g.setAttribute(
        'transform',
        `translate(${sel.center.x},${sel.center.y}) matrix(${sel.transform.a},${sel.transform.b},${sel.transform.c},${sel.transform.d},0,0)`
      )
      g.style.display = ''
      r.setAttribute('x', String(-hw))
      r.setAttribute('y', String(-hh))
      r.setAttribute('width', String(sel.width))
      r.setAttribute('height', String(sel.height))
      r.setAttribute('stroke-width', String(strokeWidth))
    })
  }, [hotGRef, selRectRef])
}
