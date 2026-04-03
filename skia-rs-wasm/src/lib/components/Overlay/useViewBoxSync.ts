import type { RefObject } from 'react'
import { useLayoutEffect, useRef } from 'react'
import { effect } from '@preact/signals-core'
import { viewport as viewportSignal } from '../../renderer/signals/pointer'

export function useViewBoxSync(
  svgRef: RefObject<SVGSVGElement | null>,
  canvasSize: { width: number; height: number }
): void {
  const canvasSizeRef = useRef(canvasSize)

  useLayoutEffect(() => {
    canvasSizeRef.current = canvasSize
  }, [canvasSize])

  // Read viewportSignal.value directly in the effect body (not via peek())
  // to create a proper reactive subscription — matching how
  // useImperativeSelectionRect reads signals and works correctly.
  useLayoutEffect(() => {
    return effect(() => {
      const vp = viewportSignal.value
      const { width, height } = canvasSizeRef.current
      const svg = svgRef.current
      if (!svg || width <= 0 || height <= 0) {
        if (svg) svg.setAttribute('viewBox', '0 0 100 100')
        return
      }
      if (!vp || !Number.isFinite(vp.zoom) || vp.zoom <= 0) {
        svg.setAttribute('viewBox', '0 0 100 100')
        return
      }
      svg.setAttribute('viewBox', `${vp.panX} ${vp.panY} ${width / vp.zoom} ${height / vp.zoom}`)
    })
  }, [svgRef])

  useLayoutEffect(() => {
    const vp = viewportSignal.peek()
    const { width, height } = canvasSize
    const svg = svgRef.current
    if (!svg || width <= 0 || height <= 0) {
      if (svg) svg.setAttribute('viewBox', '0 0 100 100')
      return
    }
    if (!vp || !Number.isFinite(vp.zoom) || vp.zoom <= 0) {
      svg.setAttribute('viewBox', '0 0 100 100')
      return
    }
    svg.setAttribute('viewBox', `${vp.panX} ${vp.panY} ${width / vp.zoom} ${height / vp.zoom}`)
  }, [canvasSize, svgRef])
}
