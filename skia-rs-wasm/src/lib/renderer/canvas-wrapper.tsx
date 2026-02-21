/**
 * React component wrapper that initializes both worker and canvas renderer
 */

import { useEffect, useRef, useState } from 'react'
import type { CanvasWrapperProps } from './types'
import { useWorkspaceStore } from './store/workspace-store'
import { useViewportShortcutsStore } from './store/shortcuts-store'
import { initRendererClient, cleanupRendererClient } from './renderer-init'
import { SelectionOverlay } from '../components/selection-overlay'
import { useViewportInteractions } from './hooks/use-viewport-interactions'
import { useMove } from './hooks/use-move'
import { useResize } from './hooks/use-resize'
import { useStreams } from './hooks/use-streams'
import { useSelection } from './hooks/use-selection'
import { cleanupWorker, initWorker } from '../worker-init'
import { initWasmModule } from '../wasm-init'

const DEFAULT_WIDTH = 800
const DEFAULT_HEIGHT = 600

export function CanvasWrapper({
  className,
  containerStyle,
  containerClassName,
  rendererOptions,
  shortcuts: initialViewportShortcuts,
}: CanvasWrapperProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [canvasSize, setCanvasSize] = useState({ width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT })
  const setViewportShortcuts = useViewportShortcutsStore((state) => state.setViewportShortcuts)
  const setModifierKeys = useViewportShortcutsStore((state) => state.setModifierKeys)

  // Apply initial shortcuts when provided (e.g. on mount or when prop changes)
  useEffect(() => {
    if (initialViewportShortcuts) {
      setViewportShortcuts(initialViewportShortcuts)
    }
  }, [initialViewportShortcuts, setViewportShortcuts])

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

  // Derive canvas size from container and keep WASM viewbox in sync
  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const syncSize = () => {
      const w = Math.round(container.clientWidth)
      const h = Math.round(container.clientHeight)
      const width = w > 0 ? w : DEFAULT_WIDTH
      const height = h > 0 ? h : DEFAULT_HEIGHT
      setCanvasSize((prev) => (prev.width === width && prev.height === height ? prev : { width, height }))
      const { renderer: r } = useWorkspaceStore.getState()
      if (r) {
        try {
          r.resize(width, height)
        } catch {
          // Context not ready yet (e.g. before initPage)
        }
      }
    }

    syncSize()
    const ro = new ResizeObserver(syncSize)
    ro.observe(container)
    return () => ro.disconnect()
  }, [])

  // When renderer becomes available, resize to current canvas size
  useEffect(() => {
    if (!renderer) return
    const { width, height } = canvasSize
    try {
      renderer.resize(width, height)
    } catch {
      // Context not ready yet
    }
  }, [renderer, canvasSize])

  // Update modifier keys on window keydown/keyup for move Shift constrain, etc.
  useEffect(() => {
    const update = (e: KeyboardEvent) => {
      setModifierKeys({
        shift: e.shiftKey,
        alt: e.altKey,
        ctrl: e.ctrlKey,
        meta: e.metaKey,
      })
    }
    const reset = () => setModifierKeys({ shift: false, alt: false, ctrl: false, meta: false })
    window.addEventListener('keydown', update)
    window.addEventListener('keyup', update)
    window.addEventListener('blur', reset)
    return () => {
      window.removeEventListener('keydown', update)
      window.removeEventListener('keyup', update)
      window.removeEventListener('blur', reset)
    }
  }, [setModifierKeys])

  useStreams(canvasRef)
  useSelection()
  useMove()
  useResize()
  useViewportInteractions({
    canvasRef,
    onViewportUpdate: () => {
      const { viewport: vp, setViewport, bumpViewportVersion } = useWorkspaceStore.getState()
      if (vp) setViewport(vp)
      bumpViewportVersion()
      if (vp) {
        requestAnimationFrame(() => {
          useWorkspaceStore.getState().setLastAppliedViewport(vp.clone())
        })
      }
    },
  })

  return (
    <div
      ref={containerRef}
      className={containerClassName}
      style={{
        width: '100%',
        height: '100%',
        minWidth: 1,
        minHeight: 1,
        position: 'relative',
        ...containerStyle,
      }}
    >
      <canvas
        ref={canvasRef}
        width={canvasSize.width}
        height={canvasSize.height}
        className={className}
        style={{ display: 'block', width: '100%', height: '100%', border: 'none', boxSizing: 'content-box' }}
      />
      <SelectionOverlay canvasSize={canvasSize} canvasRef={canvasRef} />
    </div>
  )
}
