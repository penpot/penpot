/**
 * React hook for viewport interactions
 * Manages event listeners for canvas and window interactions (wheel, mouse, keyboard)
 */

import type { RefObject } from 'react'
import { useEffect, useRef, useCallback } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { useViewportShortcutsStore } from '../store/shortcuts-store'
import type { ViewportPanModifier } from '../types'
import { mousePosition$ } from '../streams'
import { queryNodesAtPoint, pickTopmostNode } from '../selection/query-at-point'

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

interface UseViewportInteractionsParams {
  canvasRef: RefObject<HTMLCanvasElement | null>
  onViewportUpdate?: () => void
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
    viewport.zoomAt({ x, y }, zoomFactor)
    renderer.applyViewport(viewport)
    onViewportUpdate?.()
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
      mousePosition$.next({ x: screenX, y: screenY })

      const mod = e.ctrlKey || e.metaKey
      const shift = e.shiftKey
      const store = useWorkspaceStore.getState()
      const { workerClient, pageId, viewport, lastAppliedViewport, documentModel, selectedIds } = store
      const page = documentModel?.getPage(pageId ?? '')
      const viewportForHit = lastAppliedViewport ?? viewport

      if (mod) {
        store.setAreaSelectionMode(shift, shift && mod)
        setIsSelecting(true)
        return
      }

      if (!workerClient || !viewportForHit || !pageId) {
        setIsSelecting(true)
        return
      }
      queryNodesAtPoint(workerClient, pageId, viewportForHit, screenX, screenY).then(
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
            setIsSelecting(true)
          }
        }
      )
    }
  }, [canvasRef, setIsSelecting, setIsMoving, setSelectedIds, shortcuts.panMouseButton, shortcuts.panWithModifier])

  // Handle mouse move for panning
  const handleMouseMove = useCallback((e: MouseEvent) => {
    const canvasElement = canvasRef.current
    if (!canvasElement) return

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
        const vp = viewport
        const rdr = renderer
        requestAnimationFrame(() => {
          if (vp && rdr && isPanningRef.current) {
            const { dx, dy } = pendingPanDeltaRef.current
            if (dx !== 0 || dy !== 0) {
              vp.pan(dx, dy)
              rdr.applyViewport(vp)
              onViewportUpdate?.()
              pendingPanDeltaRef.current = { dx: 0, dy: 0 }
            }
          }
          pendingPanUpdateRef.current = false
        })
      }
    }
  }, [canvasRef, viewport, renderer, onViewportUpdate])

  // Handle mouse up
  const handleMouseUp = useCallback(() => {
    const canvas = canvasRef.current
    if (isPanningRef.current) {
      isPanningRef.current = false
      lastPanPosRef.current = null
      if (pendingPanDeltaRef.current.dx !== 0 || pendingPanDeltaRef.current.dy !== 0) {
        if (viewport && renderer) {
          const { dx, dy } = pendingPanDeltaRef.current
          viewport.pan(dx, dy)
          renderer.applyViewport(viewport)
          onViewportUpdate?.()
          pendingPanDeltaRef.current = { dx: 0, dy: 0 }
        }
      }
      if (canvas) {
        canvas.style.cursor = 'grab'
      }
    }

    // End selection and moving
    setIsSelecting(false)
    setIsMoving(false)
  }, [canvasRef, viewport, renderer, setIsSelecting, setIsMoving, onViewportUpdate])

  // Handle mouse enter to show grab cursor
  const handleMouseEnter = useCallback(() => {
    const canvas = canvasRef.current
    if (canvas && !isPanningRef.current) {
      canvas.style.cursor = 'grab'
    }
  }, [canvasRef])

  // Handle mouse leave to reset cursor
  const handleMouseLeave = useCallback(() => {
    const canvas = canvasRef.current
    if (canvas && !isPanningRef.current) {
      canvas.style.cursor = 'default'
    }
  }, [canvasRef])

  // Handle keyboard for panning and zooming
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (!viewport || !renderer) return

    const canvasElement = canvasRef.current
    const step = shortcuts.panStep
    if (e.code === shortcuts.panLeft) {
      e.preventDefault()
      viewport.pan(step, 0)
      renderer.applyViewport(viewport)
      onViewportUpdate?.()
      return
    }
    if (e.code === shortcuts.panRight) {
      e.preventDefault()
      viewport.pan(-step, 0)
      renderer.applyViewport(viewport)
      onViewportUpdate?.()
      return
    }
    if (e.code === shortcuts.panUp) {
      e.preventDefault()
      viewport.pan(0, step)
      renderer.applyViewport(viewport)
      onViewportUpdate?.()
      return
    }
    if (e.code === shortcuts.panDown) {
      e.preventDefault()
      viewport.pan(0, -step)
      renderer.applyViewport(viewport)
      onViewportUpdate?.()
      return
    }

    if (shortcuts.zoomInKeys.includes(e.code)) {
      e.preventDefault()
      if (canvasElement) {
        const rect = canvasElement.getBoundingClientRect()
        viewport.zoomAt({ x: rect.width / 2, y: rect.height / 2 }, shortcuts.zoomInFactor)
        renderer.applyViewport(viewport)
        onViewportUpdate?.()
      }
      return
    }
    if (shortcuts.zoomOutKeys.includes(e.code)) {
      e.preventDefault()
      if (canvasElement) {
        const rect = canvasElement.getBoundingClientRect()
        viewport.zoomAt({ x: rect.width / 2, y: rect.height / 2 }, shortcuts.zoomOutFactor)
        renderer.applyViewport(viewport)
        onViewportUpdate?.()
      }
      return
    }

    if (shortcuts.resetKeys.includes(e.code)) {
      e.preventDefault()
      viewport.reset()
      renderer.applyViewport(viewport)
      onViewportUpdate?.()
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

    return () => {
      canvas.removeEventListener('wheel', handleWheel)
      canvas.removeEventListener('mousedown', handleMouseDown)
      canvas.removeEventListener('mouseenter', handleMouseEnter)
      canvas.removeEventListener('mouseleave', handleMouseLeave)
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [canvasRef, handleWheel, handleMouseDown, handleMouseEnter, handleMouseLeave, handleMouseMove, handleMouseUp, handleKeyDown])
}

