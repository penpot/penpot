/**
 * React component wrapper that initializes both worker and canvas renderer
 */

import { useEffect, useRef, useState, useMemo } from 'react'
import type { CanvasWrapperProps } from './types'
import { useWorkspaceStore } from './store/workspace-store'
import { useViewportShortcutsStore } from './store/shortcuts-store'
import { getSelectionBounds } from './selection-bounds'
import { initRendererClient, cleanupRendererClient } from './renderer-init'
import { useViewportInteractions } from './hooks/use-viewport-interactions'
import { useMove } from './hooks/use-move'
import { useStreams } from './hooks/use-streams'
import { useSelection } from './hooks/use-selection'
import { cleanupWorker, initWorker } from '../worker-init'
import { initWasmModule } from '../wasm-init'

const DEFAULT_WIDTH = 800
const DEFAULT_HEIGHT = 600

const SELECTION_STROKE = 'var(--color-accent-tertiary, #0d7377)'
const SELECTION_STROKE_WIDTH = 1

export function CanvasWrapper({
  className,
  style,
  rendererOptions,
  shortcuts: initialViewportShortcuts,
}: CanvasWrapperProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [canvasSize, setCanvasSize] = useState({ width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT })
  const setViewportShortcuts = useViewportShortcutsStore((state) => state.setViewportShortcuts)
  const setModifierKeys = useViewportShortcutsStore((state) => state.setModifierKeys)

  const selectedIds = useWorkspaceStore((state) => state.selectedIds)
  const selectedNodes = useWorkspaceStore((state) => state.selectedNodes)
  const viewport = useWorkspaceStore((state) => state.viewport)
  const viewportVersion = useWorkspaceStore((state) => state.viewportVersion)
  const isSelecting = useWorkspaceStore((state) => state.isSelecting)
  const selectionRect = useWorkspaceStore((state) => state.selectionRect)
  const isMoving = useWorkspaceStore((state) => state.isMoving)
  const movePreviewDelta = useWorkspaceStore((state) => state.movePreviewDelta)

  const selectionBounds = useMemo(() => getSelectionBounds(selectedNodes), [selectedNodes])

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

  const showSelectionRect = selectedIds.size > 0 && selectionBounds && viewport
  const effectiveBounds =
    showSelectionRect && selectionBounds
      ? isMoving && movePreviewDelta
        ? {
            x: selectionBounds.x + movePreviewDelta.x,
            y: selectionBounds.y + movePreviewDelta.y,
            width: selectionBounds.width,
            height: selectionBounds.height,
          }
        : selectionBounds
      : null
  const showAreaMarquee = isSelecting && selectionRect != null && viewport != null
  const areaMarqueeWorld =
    showAreaMarquee && viewport && selectionRect
      ? {
          x: viewport.panX + (selectionRect.x ?? (selectionRect as { x1?: number }).x1 ?? 0) / viewport.zoom,
          y: viewport.panY + (selectionRect.y ?? (selectionRect as { y1?: number }).y1 ?? 0) / viewport.zoom,
          width: (selectionRect.width ?? 0) / viewport.zoom,
          height: (selectionRect.height ?? 0) / viewport.zoom,
        }
      : null
  const viewBox =
    viewport && canvasSize.width > 0 && canvasSize.height > 0
      ? `${viewport.panX} ${viewport.panY} ${canvasSize.width / viewport.zoom} ${canvasSize.height / viewport.zoom}`
      : '0 0 100 100'

  return (
    <div
      ref={containerRef}
      style={{ width: '100%', height: '100%', minWidth: 1, minHeight: 1, position: 'relative' }}
    >
      <canvas
        ref={canvasRef}
        width={canvasSize.width}
        height={canvasSize.height}
        className={className}
        style={{ ...style, display: 'block', width: '100%', height: '100%' }}
      />
      <svg
        key={`selection-overlay-${viewportVersion}`}
        aria-hidden
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: '100%',
          height: '100%',
          pointerEvents: 'none',
        }}
        viewBox={viewBox}
        preserveAspectRatio="xMidYMid meet"
      >
        {showSelectionRect && effectiveBounds && (
          <rect
            x={effectiveBounds.x}
            y={effectiveBounds.y}
            width={effectiveBounds.width}
            height={effectiveBounds.height}
            fill="none"
            stroke={SELECTION_STROKE}
            strokeWidth={SELECTION_STROKE_WIDTH / (viewport?.zoom ?? 1)}
          />
        )}
        {showAreaMarquee && areaMarqueeWorld && (
          <rect
            x={areaMarqueeWorld.x}
            y={areaMarqueeWorld.y}
            width={areaMarqueeWorld.width}
            height={areaMarqueeWorld.height}
            fill="rgba(37,99,235,0.1)"
            stroke="#2563eb"
            strokeWidth={SELECTION_STROKE_WIDTH / (viewport?.zoom ?? 1)}
          />
        )}
      </svg>
    </div>
  )
}
