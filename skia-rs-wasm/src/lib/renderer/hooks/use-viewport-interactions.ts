/**
 * React hook for viewport interactions
 * Manages event listeners for canvas and window interactions (wheel, mouse, keyboard)
 */

import type { RefObject } from 'react'
import { useEffect, useRef, useCallback } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { useViewportShortcutsStore } from '../store/shortcuts-store'
import { getActiveOrSinglePageId, getPage } from '../store/doc-proxy'
import { Viewport, screenToWorld } from '../viewport'
import type { ViewportPanModifier, SelectionRectResult } from '../types'
import { mousePosition$ } from '../streams'
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
  // Single state selectors (for useCallback deps)
  const viewport = useWorkspaceStore((state) => state.viewport)
  const renderer = useWorkspaceStore((state) => state.renderer)
  const setIsSelecting = useWorkspaceStore((state) => state.setIsSelecting)
  const setIsMoving = useWorkspaceStore((state) => state.setIsMoving)
  const setSelectedIds = useWorkspaceStore((state) => state.setSelectedIds)
  const setIsPanning = useWorkspaceStore((state) => state.setIsPanning)
  const shortcuts = useViewportShortcutsStore((state) => state.viewportShortcuts)
  // Refs for panning state
  const isPanningRef = useRef<boolean>(false)
  const lastPanPosRef = useRef<{ x: number; y: number } | null>(null)
  const pendingPanUpdateRef = useRef<boolean>(false)
  const pendingPanDeltaRef = useRef<{ dx: number; dy: number }>({ dx: 0, dy: 0 })

  // Handle mouse wheel for zooming
  const handleWheel = useCallback((e: WheelEvent) => {
    const canvasElement = canvasRef.current
    if (!viewport || !renderer || !canvasElement) return
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
    const next = Viewport.from(viewport)
    next.zoomAt({ x, y }, zoomFactor)
    renderer.applyViewport(next)
    onViewportUpdate?.(next)
  }, [canvasRef, viewport, renderer, onViewportUpdate, shortcuts.wheelZoomEnabled, shortcuts.wheelScalePerPixel])

  // Handle mouse down
  const handleMouseDown = useCallback((e: MouseEvent) => {
    const canvasElement = canvasRef.current
    if (!canvasElement) return

    const panWithButton = e.button === shortcuts.panMouseButton
    const panWithMod = e.button === 0 && hasPanModifier(e, shortcuts.panWithModifier)
    if (panWithButton || panWithMod) {
      e.preventDefault()
      isPanningRef.current = true
      setIsPanning(true)
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

      if (useWorkspaceStore.getState().drawTool === 'rect') {
        mousePosition$.next({ x: screenX, y: screenY })
        useWorkspaceStore.getState().setIsDrawingShape(true)
        return
      }

      mousePosition$.next({ x: screenX, y: screenY })

      const mod = e.ctrlKey || e.metaKey
      const shift = e.shiftKey
      const store = useWorkspaceStore.getState()
      const { workerClient, pageId, viewport, lastAppliedViewport, selectedIds } = store
      const hitPageId = pageId ?? getActiveOrSinglePageId()
      const page = hitPageId ? getPage(hitPageId) : undefined
      const viewportForHit = lastAppliedViewport ?? viewport

      if (mod) {
        store.setAreaSelectionMode(shift, shift && mod)
        setIsSelecting(true)
        return
      }

      if (!workerClient || !viewportForHit || !hitPageId) {
        setIsSelecting(true)
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
            setIsMoving(true)
          } else {
            // Fallback: click in empty space (e.g. inside stroke-only shape) but inside selection bounds → start move
            const store = useWorkspaceStore.getState()
            const { selectedIds: currentIds, wasmSelectionRect } = store
            if (
              currentIds.size > 0 &&
              wasmSelectionRect != null &&
              viewportForHit != null
            ) {
              const world = screenToWorld(viewportForHit, screenX, screenY)
              if (isPointInSelectionBounds(world, wasmSelectionRect)) {
                setIsMoving(true)
                return
              }
            }
            setIsSelecting(true)
          }
        }
      )
    }
  }, [canvasRef, setIsSelecting, setIsMoving, setSelectedIds, setIsPanning, shortcuts.panMouseButton, shortcuts.panWithModifier])

  // Handle mouse move for panning and cursor (grab only when pan modifier held; resize cursor when resizing)
  const handleMouseMove = useCallback((e: MouseEvent) => {
    const canvasElement = canvasRef.current
    if (!canvasElement) return

    if (!isPanningRef.current) {
      const store = useWorkspaceStore.getState()
      const { isResizing, resizeHandle, wasmSelectionRect } = store
      if (isResizing && resizeHandle) {
        const rotation = wasmSelectionRect != null ? matrixToRotationDeg(wasmSelectionRect.transform) : undefined
        const halfFlip = wasmSelectionRect != null ? matrixHasHalfFlip(wasmSelectionRect.transform) : false
        canvasElement.style.cursor = getResizeCursor(resizeHandle, rotation, halfFlip)
      } else if (e.target === canvasElement) {
        const drawTool = useWorkspaceStore.getState().drawTool
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
        const vpData = viewport
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
  }, [canvasRef, viewport, renderer, onViewportUpdate, shortcuts.panWithModifier])

  // Handle mouse up
  const handleMouseUp = useCallback(() => {
    const canvas = canvasRef.current
    if (isPanningRef.current) {
      setIsPanning(false)
      isPanningRef.current = false
      lastPanPosRef.current = null
      if (pendingPanDeltaRef.current.dx !== 0 || pendingPanDeltaRef.current.dy !== 0) {
        if (viewport && renderer) {
          const { dx, dy } = pendingPanDeltaRef.current
          const next = Viewport.from(viewport)
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

    // End selection. isMoving and isResizing are cleared by their handlers after commit (so overlay uses updated bounds
    // and a quick second resize sees the latest node). Do not clear isRotating here: the rotate handler clears it after
    // applyChanges resolves; clearing here would dispose the subscription before commitOnRelease runs.
    setIsSelecting(false)
  }, [canvasRef, viewport, renderer, setIsSelecting, setIsPanning, onViewportUpdate])

  // Handle mouse enter: normal cursor unless pan modifier is held (updated in mousemove)
  const handleMouseEnter = useCallback(() => {
    const canvas = canvasRef.current
    if (canvas && !isPanningRef.current) {
      const drawTool = useWorkspaceStore.getState().drawTool
      canvas.style.cursor = drawTool === 'rect' ? 'crosshair' : 'default'
    }
  }, [canvasRef])

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
    if (e.code === 'Escape' && useWorkspaceStore.getState().drawTool != null) {
      e.preventDefault()
      useWorkspaceStore.getState().setDrawTool(null)
      const canvasElement = canvasRef.current
      if (canvasElement) canvasElement.style.cursor = 'default'
      return
    }

    if (e.code === 'KeyR' && !e.metaKey && !e.ctrlKey && !e.altKey) {
      const el = e.target as HTMLElement | null
      if (!el?.closest('input, textarea, select, [contenteditable="true"]')) {
        e.preventDefault()
        const state = useWorkspaceStore.getState()
        const next = state.drawTool === 'rect' ? null : 'rect'
        state.setDrawTool(next)
        const canvasElement = canvasRef.current
        if (canvasElement) {
          canvasElement.style.cursor = next === 'rect' ? 'crosshair' : 'default'
        }
        return
      }
    }

    if (!viewport || !renderer) return

    const canvasElement = canvasRef.current
    const step = shortcuts.panStep
    if (e.code === shortcuts.panLeft) {
      e.preventDefault()
      const next = Viewport.from(viewport)
      next.pan(step, 0)
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
      return
    }
    if (e.code === shortcuts.panRight) {
      e.preventDefault()
      const next = Viewport.from(viewport)
      next.pan(-step, 0)
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
      return
    }
    if (e.code === shortcuts.panUp) {
      e.preventDefault()
      const next = Viewport.from(viewport)
      next.pan(0, step)
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
      return
    }
    if (e.code === shortcuts.panDown) {
      e.preventDefault()
      const next = Viewport.from(viewport)
      next.pan(0, -step)
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
      return
    }

    if (shortcuts.zoomInKeys.includes(e.code)) {
      e.preventDefault()
      if (canvasElement) {
        const rect = canvasElement.getBoundingClientRect()
        const next = Viewport.from(viewport)
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
        const next = Viewport.from(viewport)
        next.zoomAt({ x: rect.width / 2, y: rect.height / 2 }, shortcuts.zoomOutFactor)
        renderer.applyViewport(next)
        onViewportUpdate?.(next)
      }
      return
    }

    if (shortcuts.resetKeys.includes(e.code)) {
      e.preventDefault()
      const next = Viewport.from(viewport)
      next.reset()
      renderer.applyViewport(next)
      onViewportUpdate?.(next)
    }
  }, [canvasRef, viewport, renderer, onViewportUpdate, shortcuts])

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

