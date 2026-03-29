/**
 * React hook for viewport interactions
 * Manages event listeners for canvas and window interactions (wheel, mouse, keyboard)
 */

import type { RefObject } from 'react'
import { useEffect, useRef, useCallback } from 'react'
import { getSelectedIdsSet, setSelectedIds } from '../store/document-selection'
import { useWorkspaceStore } from '../store/workspace-store'
import { useCanvasActor } from '../machine/canvas-actor-context'
import { useViewportShortcutsStore } from '../store/shortcuts-store'
import { getActiveOrSinglePageId, getPage } from '../store/doc-proxy'
import { Viewport, screenToWorld } from '../viewport'
import type { ViewportPanModifier, SelectionRectResult } from '../types'
import { pointerPos, viewport } from '../signals/pointer'
import { wasmSelectionRect } from '../signals/selection'
import { queryNodesAtPoint, pickTopmostNode } from '../selection/query-at-point'
import { getResizeCursor, matrixHasHalfFlip, matrixToRotationDeg } from '../../components/selection-overlay/constants'

function hasPanModifier(e: MouseEvent, mod: ViewportPanModifier): boolean {
  if (mod === null) return false
  switch (mod) {
    case 'shift': return e.shiftKey
    case 'alt': return e.altKey
    case 'ctrl': return e.ctrlKey
    case 'meta': return e.metaKey
    default: return false
  }
}

function isPanModifierKey(e: KeyboardEvent, mod: ViewportPanModifier): boolean {
  if (mod === null) return false
  switch (mod) {
    case 'shift': return e.key === 'Shift'
    case 'alt': return e.key === 'Alt'
    case 'ctrl': return e.key === 'Control'
    case 'meta': return e.key === 'Meta'
    default: return false
  }
}

/** True if world point is inside the selection rect (respects rotation). */
function isPointInSelectionBounds(
  point: { x: number; y: number },
  sel: SelectionRectResult
): boolean {
  const { center, width, height, transform } = sel
  const dx = point.x - center.x
  const dy = point.y - center.y
  const { a, b, c, d } = transform
  const det = a * d - b * c
  if (Math.abs(det) < 1e-10) return false
  const localX = (d * dx - c * dy) / det
  const localY = (-b * dx + a * dy) / det
  const hw = width / 2
  const hh = height / 2
  return localX >= -hw && localX <= hw && localY >= -hh && localY <= hh
}

interface UseViewportInteractionsParams {
  canvasRef: RefObject<HTMLCanvasElement | null>
  /** Called with the new Viewport instance after each pan/zoom; consumer should update store from it. */
  onViewportUpdate?: (next: Viewport) => void
}

export function useViewportInteractions({
  canvasRef,
  onViewportUpdate,
}: UseViewportInteractionsParams) {
  const canvasActor = useCanvasActor()
  const renderer = useWorkspaceStore((state) => state.renderer)
  const shortcuts = useViewportShortcutsStore((state) => state.viewportShortcuts)
  // Refs for panning state
  const isPanningRef = useRef<boolean>(false)
  const lastPanPosRef = useRef<{ x: number; y: number } | null>(null)
  const pendingPanUpdateRef = useRef<boolean>(false)
  const pendingPanDeltaRef = useRef<{ dx: number; dy: number }>({ dx: 0, dy: 0 })

  // Handle mouse wheel for zooming
  const handleWheel = useCallback((e: WheelEvent) => {
    const canvasElement = canvasRef.current
    if (!viewport.value || !renderer || !canvasElement) return
    if (!shortcuts.wheelZoomEnabled) return

    e.preventDefault()
    e.stopPropagation()

    // Get mouse position relative to canvas (use same coordinate space as viewport)
    const rect = canvasElement.getBoundingClientRect()
    const x = e.clientX - rect.left
    const y = e.clientY - rect.top

    const scalePerPixel = shortcuts.wheelScalePerPixel
    const absDelta = Math.abs(e.deltaY) + Math.abs(e.deltaX)
    const scale = 1 + scalePerPixel * absDelta
    const zoomFactor = e.deltaY < 0 ? scale : 1 / scale
    const next = Viewport.from(viewport.value)
    next.zoomAt({ x, y }, zoomFactor)
    renderer.applyViewport(next)
    onViewportUpdate?.(next)
  }, [canvasRef, renderer, onViewportUpdate, shortcuts.wheelZoomEnabled, shortcuts.wheelScalePerPixel])

  // Handle mouse down
  const handleMouseDown = useCallback((e: MouseEvent) => {
    const canvasElement = canvasRef.current
    if (!canvasElement) return

    const panWithButton = e.button === shortcuts.panMouseButton
    const panWithMod = e.button === 0 && hasPanModifier(e, shortcuts.panWithModifier)
    if (panWithButton || panWithMod) {
      e.preventDefault()
      isPanningRef.current = true
      canvasActor.send({ type: 'PAN_START' })
      lastPanPosRef.current = { x: e.clientX, y: e.clientY }
      canvasElement.style.cursor = 'grabbing'
      return
    }

    // Left mouse button for selection/moving
    if (e.button === 0) {
      e.preventDefault()

      const rect = canvasElement.getBoundingClientRect()
      const screenX = e.clientX - rect.left
      const screenY = e.clientY - rect.top

      if (canvasActor.getSnapshot().context.drawTool === 'rect') {
        pointerPos.value = { x: screenX, y: screenY }
        canvasActor.send({ type: 'POINTER_DOWN_DRAW' })
        return
      }

      pointerPos.value = { x: screenX, y: screenY }

      const mod = e.ctrlKey || e.metaKey
      const shift = e.shiftKey
      const store = useWorkspaceStore.getState()
      const { workerClient } = store
      const selectedIds = getSelectedIdsSet()
      const hitPageId = getActiveOrSinglePageId()
      const page = hitPageId ? getPage(hitPageId) : undefined
      const viewportForHit = viewport.value

      if (mod) {
        canvasActor.send({ type: 'POINTER_DOWN_ON_CANVAS', append: shift, remove: shift && mod })
        return
      }

      if (!workerClient || !viewportForHit || !hitPageId) {
        canvasActor.send({ type: 'POINTER_DOWN_ON_CANVAS', append: false, remove: false })
        return
      }
      queryNodesAtPoint(workerClient, hitPageId, viewportForHit, screenX, screenY).then(
        (ids) => {
          const topId = pickTopmostNode(page, ids)
          if (topId) {
            if (shift) {
              const next = new Set(selectedIds)
              if (next.has(topId)) next.delete(topId)
              else next.add(topId)
              setSelectedIds(next)
            } else {
              // Keep full selection when clicking an already-selected node (group drag)
              if (!selectedIds.has(topId)) {
                setSelectedIds(new Set([topId]))
              }
            }
            canvasActor.send({ type: 'POINTER_DOWN_ON_SELECTION', position: { x: screenX, y: screenY } })
          } else {
            // Fallback: click in empty space (e.g. inside stroke-only shape) but inside selection bounds → start move
            const currentIds = getSelectedIdsSet()
            const wasmRect = wasmSelectionRect.peek()
            if (
              currentIds.size > 0 &&
              wasmRect != null &&
              viewportForHit != null
            ) {
              const world = screenToWorld(viewportForHit, screenX, screenY)
              if (isPointInSelectionBounds(world, wasmRect)) {
                canvasActor.send({ type: 'POINTER_DOWN_ON_SELECTION', position: { x: screenX, y: screenY } })
                return
              }
            }
            canvasActor.send({ type: 'POINTER_DOWN_ON_CANVAS', append: false, remove: false })
          }
        }
      )
    }
  }, [canvasRef, canvasActor, shortcuts.panMouseButton, shortcuts.panWithModifier])

  // Handle mouse move for panning and cursor (grab only when pan modifier held; resize cursor when resizing)
  const handleMouseMove = useCallback((e: MouseEvent) => {
    const canvasElement = canvasRef.current
    if (!canvasElement) return

    if (!isPanningRef.current) {
      const snap = canvasActor.getSnapshot()
      const wasmRect = wasmSelectionRect.peek()
      if (snap.matches('resizing') && snap.context.resizeHandle) {
        const rotation = wasmRect != null ? matrixToRotationDeg(wasmRect.transform) : undefined
        const halfFlip = wasmRect != null ? matrixHasHalfFlip(wasmRect.transform) : false
        canvasElement.style.cursor = getResizeCursor(snap.context.resizeHandle, rotation, halfFlip)
      } else if (e.target === canvasElement) {
        const drawTool = snap.context.drawTool
        if (drawTool === 'rect') {
          canvasElement.style.cursor = 'crosshair'
        } else {
          canvasElement.style.cursor = hasPanModifier(e, shortcuts.panWithModifier) ? 'grab' : 'default'
        }
      }
    }

    if (isPanningRef.current && lastPanPosRef.current) {
      e.preventDefault()
      const dx = e.clientX - lastPanPosRef.current.x
      const dy = e.clientY - lastPanPosRef.current.y

      // Update last position immediately
      lastPanPosRef.current = { x: e.clientX, y: e.clientY }

      // Accumulate delta (clientX/clientY are in CSS pixels per DOM spec)
      pendingPanDeltaRef.current.dx += dx
      pendingPanDeltaRef.current.dy += dy

      // Schedule update on next animation frame if not already scheduled
      if (!pendingPanUpdateRef.current) {
        pendingPanUpdateRef.current = true
        const vpData = viewport.value
        const rdr = renderer
        requestAnimationFrame(() => {
          if (vpData && rdr && isPanningRef.current) {
            const { dx, dy } = pendingPanDeltaRef.current
            if (dx !== 0 || dy !== 0) {
              const next = Viewport.from(vpData)
              next.pan(dx, dy)
              rdr.applyViewport(next)
              onViewportUpdate?.(next)
              pendingPanDeltaRef.current = { dx: 0, dy: 0 }
            }
          }
          pendingPanUpdateRef.current = false
        })
      }
    }
  }, [canvasRef, canvasActor, renderer, onViewportUpdate, shortcuts.panWithModifier])

  // Handle mouse up
  const handleMouseUp = useCallback(() => {
    const canvas = canvasRef.current
    if (isPanningRef.current) {
      canvasActor.send({ type: 'PAN_END' })
      isPanningRef.current = false
      lastPanPosRef.current = null
      if (pendingPanDeltaRef.current.dx !== 0 || pendingPanDeltaRef.current.dy !== 0) {
        if (viewport.value && renderer) {
          const { dx, dy } = pendingPanDeltaRef.current
          const next = Viewport.from(viewport.value)
          next.pan(dx, dy)
          renderer.applyViewport(next)
          onViewportUpdate?.(next)
          pendingPanDeltaRef.current = { dx: 0, dy: 0 }
        }
      }
      if (canvas) {
        canvas.style.cursor = 'default'
      }
    }

  }, [canvasRef, canvasActor, renderer, onViewportUpdate])

  // Handle mouse enter: normal cursor unless pan modifier is held (updated in mousemove)
  const handleMouseEnter = useCallback(() => {
    const canvas = canvasRef.current
    if (canvas && !isPanningRef.current) {
      const drawTool = canvasActor.getSnapshot().context.drawTool
      canvas.style.cursor = drawTool === 'rect' ? 'crosshair' : 'default'
    }
  }, [canvasRef, canvasActor])

  // Handle mouse leave to reset cursor
  const handleMouseLeave = useCallback(() => {
    const canvas = canvasRef.current
    if (canvas && !isPanningRef.current) {
      canvas.style.cursor = 'default'
    }
  }, [canvasRef])

  // When pan modifier key is released, revert to default cursor (if not panning)
  const handleKeyUp = useCallback((e: KeyboardEvent) => {
    const canvas = canvasRef.current
    if (canvas && !isPanningRef.current && isPanModifierKey(e, shortcuts.panWithModifier)) {
      canvas.style.cursor = 'default'
    }
  }, [canvasRef, shortcuts.panWithModifier])

  // Handle keyboard for panning and zooming
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (e.code === 'Escape' && canvasActor.getSnapshot().context.drawTool != null) {
      e.preventDefault()
      canvasActor.send({ type: 'DRAW_TOOL_DEACTIVATE' })
      const canvasElement = canvasRef.current
      if (canvasElement) canvasElement.style.cursor = 'default'
      return
    }

    if (e.code === 'KeyR' && !e.metaKey && !e.ctrlKey && !e.altKey) {
      const el = e.target as HTMLElement | null
      if (!el?.closest('input, textarea, select, [contenteditable="true"]')) {
        e.preventDefault()
        const active = canvasActor.getSnapshot().context.drawTool === 'rect'
        if (active) {
          canvasActor.send({ type: 'DRAW_TOOL_DEACTIVATE' })
        } else {
          canvasActor.send({ type: 'DRAW_TOOL_ACTIVATE', tool: 'rect' })
        }
        const canvasElement = canvasRef.current
        if (canvasElement) {
          canvasElement.style.cursor = active ? 'default' : 'crosshair'
        }
        return
      }
    }

    const vp = viewport.value
    if (!vp || !renderer) return

    const canvasElement = canvasRef.current
    const step = shortcuts.panStep
    if (e.code === shortcuts.panLeft) {
      e.preventDefault()
      const next = Viewport.from(vp)
      next.pan(step, 0)
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
      return
    }
    if (e.code === shortcuts.panRight) {
      e.preventDefault()
      const next = Viewport.from(vp)
      next.pan(-step, 0)
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
      return
    }
    if (e.code === shortcuts.panUp) {
      e.preventDefault()
      const next = Viewport.from(vp)
      next.pan(0, step)
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
      return
    }
    if (e.code === shortcuts.panDown) {
      e.preventDefault()
      const next = Viewport.from(vp)
      next.pan(0, -step)
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
      return
    }

    if (shortcuts.zoomInKeys.includes(e.code)) {
      e.preventDefault()
      if (canvasElement) {
        const rect = canvasElement.getBoundingClientRect()
        const next = Viewport.from(vp)
        next.zoomAt({ x: rect.width / 2, y: rect.height / 2 }, shortcuts.zoomInFactor)
        renderer.applyViewport(next)
        onViewportUpdate?.(next)
      }
      return
    }
    if (shortcuts.zoomOutKeys.includes(e.code)) {
      e.preventDefault()
      if (canvasElement) {
        const rect = canvasElement.getBoundingClientRect()
        const next = Viewport.from(vp)
        next.zoomAt({ x: rect.width / 2, y: rect.height / 2 }, shortcuts.zoomOutFactor)
        renderer.applyViewport(next)
        onViewportUpdate?.(next)
      }
      return
    }

    if (shortcuts.resetKeys.includes(e.code)) {
      e.preventDefault()
      const next = Viewport.from(vp)
      next.reset()
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
    }
  }, [canvasRef, canvasActor, renderer, onViewportUpdate, shortcuts])

  // Set up event listeners (read ref inside effect, not during render)
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    canvas.addEventListener('wheel', handleWheel, { passive: false })
    canvas.addEventListener('mousedown', handleMouseDown)
    canvas.addEventListener('mouseenter', handleMouseEnter)
    canvas.addEventListener('mouseleave', handleMouseLeave)
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('keyup', handleKeyUp)

    return () => {
      canvas.removeEventListener('wheel', handleWheel)
      canvas.removeEventListener('mousedown', handleMouseDown)
      canvas.removeEventListener('mouseenter', handleMouseEnter)
      canvas.removeEventListener('mouseleave', handleMouseLeave)
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('keyup', handleKeyUp)
    }
  }, [canvasRef, handleWheel, handleMouseDown, handleMouseEnter, handleMouseLeave, handleMouseMove, handleMouseUp, handleKeyDown, handleKeyUp])
}

