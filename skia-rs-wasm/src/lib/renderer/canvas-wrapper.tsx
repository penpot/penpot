/**
 * React component wrapper that initializes both worker and canvas renderer
 */

import { useEffect, useRef } from 'react'
import type { CanvasWrapperProps } from './types'
import { useWorkspaceStore } from './store/workspace-store'
import { initRendererClient, cleanupRendererClient } from './renderer-init'
import { useViewportInteractions } from './hooks/use-viewport-interactions'
import { useMove } from './hooks/use-move'
import { useStreams } from './hooks/use-streams'
import { useSelection } from './hooks/use-selection'
import { cleanupWorker, initWorker } from '../worker-init'
import { initWasmModule } from '../wasm-init'

export function CanvasWrapper({
  width = 800,
  height = 600,
  className,
  style,
  rendererOptions,
}: CanvasWrapperProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    console.log('[MOVE_DEBUG] CanvasWrapper mounted - skia-rs-wasm move handler is active')
    return () => console.log('[MOVE_DEBUG] CanvasWrapper unmounted')
  }, [])
  const { workerClient, wasmModule, renderer } = useWorkspaceStore()

  useEffect(() => {
    initWasmModule('/wasm/render-wasm.js').catch((error) => {
      console.error('Failed to load WASM module:', error)
    })
    initWorker().then(() => {
      console.log('Worker initialized')
    }).catch((error) => {
      console.error('Failed to initialize worker:', error)
    })

    return () => {
      console.log('Cleaning up worker')
      cleanupWorker()
    }
  }, [])

  useEffect(() => {
    if (!workerClient || !canvasRef.current || !wasmModule) {
      return
    }
    initRendererClient(canvasRef.current, rendererOptions).then(() => {
      console.log('Renderer initialized')
    }).catch((error) => {
      console.error('Failed to initialize renderer:', error)
    })

    return () => {
      console.log('Cleaning up renderer client')
      cleanupRendererClient()
    }
  }, [workerClient, wasmModule, rendererOptions])

  // Keep WASM viewbox in sync with canvas display size so pan/zoom scale matches cursor
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !renderer) return

    const syncSize = () => {
      const w = Math.round(canvas.clientWidth || canvas.width)
      const h = Math.round(canvas.clientHeight || canvas.height)
      if (w > 0 && h > 0) {
        try {
          renderer.resize(w, h)
        } catch {
          // Context not ready yet (e.g. before initPage)
        }
      }
    }

    syncSize()
    const ro = new ResizeObserver(syncSize)
    ro.observe(canvas)
    return () => ro.disconnect()
  }, [renderer])

  useStreams(canvasRef.current)
  useSelection()
  useMove()
  useViewportInteractions({
    canvasElement: canvasRef.current,
    onViewportUpdate: () => {
      const { viewport: vp, setViewport, bumpViewportVersion } = useWorkspaceStore.getState()
      if (vp) setViewport(vp)
      bumpViewportVersion()
    },
  })

  return (
    <canvas
      ref={canvasRef}
      width={width}
      height={height}
      className={className}
      style={{ ...style, display: 'block' }}
    />
  )
}

