import type { RefObject } from 'react'
import { useEffect, useLayoutEffect, useRef } from 'react'
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

  useEffect(() => {
    function applyViewBox() {
      const vp = viewportSignal.peek()
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
    }
    applyViewBox()
    return effect(() => {
      void viewportSignal.value
      applyViewBox()
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
