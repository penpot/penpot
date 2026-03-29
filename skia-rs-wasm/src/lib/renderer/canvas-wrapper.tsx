/**
 * React component wrapper that initializes both worker and canvas renderer.
 * Mounts the canvas interaction actor (`canvasMachine`) here — library entry point for XState context.
 */

import { useEffect, useRef, useState } from 'react'
import { useActorRef } from '@xstate/react'
import { cn } from '@/lib/utils'
import type { CanvasWrapperProps } from './types'
import { useWorkspaceStore } from './store/workspace-store'
import { useViewportShortcutsStore } from './store/shortcuts-store'
import { modAlt, modCtrl, modMeta, modShift, viewport } from './signals/pointer'
import { initRendererClient, cleanupRendererClient } from './renderer-init'
import { SelectionOverlay } from '../components/selection-overlay/SelectionOverlay'
import { useViewportInteractions } from './hooks/use-viewport-interactions'
import { useStreams } from './hooks/use-streams'
import { cleanupWorker, initWorker } from '../worker-init'
import { initWasmModule } from '../wasm-init'
import { canvasMachine } from './machine/canvas-machine'
import { CanvasActorProvider } from './machine/canvas-actor-context'

const DEFAULT_WIDTH = 800
const DEFAULT_HEIGHT = 600

function CanvasWorkspace({
  className,
  containerStyle,
  containerClassName,
  startSlot,
  endSlot,
  workspaceClassName,
  rendererOptions,
  shortcuts: initialViewportShortcuts,
  wasmPath = '/wasm/render-wasm.js',
  workerScriptUrl,
}: Omit<CanvasWrapperProps, 'overlays'>) {
  const containerRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [canvasSize, setCanvasSize] = useState({ width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT })
  const setViewportShortcuts = useViewportShortcutsStore((state) => state.setViewportShortcuts)

  // Apply initial shortcuts when provided (e.g. on mount or when prop changes)
  useEffect(() => {
    if (initialViewportShortcuts) {
      setViewportShortcuts(initialViewportShortcuts)
    }
  }, [initialViewportShortcuts, setViewportShortcuts])

  const { workerClient, wasmModule, renderer } = useWorkspaceStore()

  useEffect(() => {
    initWasmModule(wasmPath).catch((error) => {
      console.error('Failed to load WASM module:', error)
    })
    initWorker(workerScriptUrl).then(() => {
      console.log('Worker initialized')
    }).catch((error) => {
      console.error('Failed to initialize worker:', error)
    })

    return () => {
      console.log('Cleaning up worker')
      cleanupWorker()
    }
  }, [wasmPath, workerScriptUrl])

  useEffect(() => {
    if (!workerClient || !wasmModule) return
    const canvas = canvasRef.current
    if (!canvas) return
    initRendererClient(canvas, rendererOptions).then(() => {
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
      modShift.value = e.shiftKey
      modAlt.value = e.altKey
      modCtrl.value = e.ctrlKey
      modMeta.value = e.metaKey
    }
    const reset = () => {
      modShift.value = false
      modAlt.value = false
      modCtrl.value = false
      modMeta.value = false
    }
    window.addEventListener('keydown', update)
    window.addEventListener('keyup', update)
    window.addEventListener('blur', reset)
    return () => {
      window.removeEventListener('keydown', update)
      window.removeEventListener('keyup', update)
      window.removeEventListener('blur', reset)
    }
  }, [])

  useStreams(canvasRef)
  useViewportInteractions({
    canvasRef,
    onViewportUpdate: (next) => {
      viewport.value = { panX: next.panX, panY: next.panY, zoom: next.zoom }
    },
  })

  const hasSlots = startSlot != null || endSlot != null

  const canvasColumn = (
    <div
      ref={containerRef}
      className={cn('relative min-h-0 min-w-0', hasSlots ? 'flex-1' : 'h-full w-full', containerClassName)}
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

  if (!hasSlots) {
    return canvasColumn
  }

  return (
    <div
      className={cn('flex h-full min-h-0 min-w-0 w-full flex-row', workspaceClassName)}
    >
      {startSlot}
      {canvasColumn}
      {endSlot}
    </div>
  )
}

export function CanvasWrapper({ overlays, ...workspaceProps }: CanvasWrapperProps) {
  const canvasActorRef = useActorRef(canvasMachine)
  return (
    <CanvasActorProvider actorRef={canvasActorRef}>
      <CanvasWorkspace {...workspaceProps} />
      {overlays}
    </CanvasActorProvider>
  )
}
