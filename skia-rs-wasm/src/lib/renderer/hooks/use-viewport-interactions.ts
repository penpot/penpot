/**
 * React hook for viewport interactions
 * Manages event listeners for canvas and window interactions (wheel, mouse, keyboard)
 */

import { useEffect, useRef, useCallback } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'
import { mousePosition$ } from '../streams'

interface UseViewportInteractionsParams {
  canvasElement: HTMLCanvasElement | null
  onViewportUpdate?: () => void
}

export function useViewportInteractions({
  canvasElement,
  onViewportUpdate,
}: UseViewportInteractionsParams) {
  // Single state selectors (for useCallback deps)
  const viewport = useWorkspaceStore((state) => state.viewport)
  const renderer = useWorkspaceStore((state) => state.renderer)
  const selectedIds = useWorkspaceStore((state) => state.selectedIds)
  const setIsSelecting = useWorkspaceStore((state) => state.setIsSelecting)
  const setIsMoving = useWorkspaceStore((state) => state.setIsMoving)
  // Refs for panning state
  const isPanningRef = useRef<boolean>(false)
  const lastPanPosRef = useRef<{ x: number; y: number } | null>(null)
  const pendingPanUpdateRef = useRef<boolean>(false)
  const pendingPanDeltaRef = useRef<{ dx: number; dy: number }>({ dx: 0, dy: 0 })

  // Handle mouse wheel for zooming
  const handleWheel = useCallback((e: WheelEvent) => {
    if (!viewport || !renderer || !canvasElement) return

    e.preventDefault()
    e.stopPropagation()

    // Get mouse position relative to canvas
    const rect = canvasElement.getBoundingClientRect()
    const x = e.clientX - rect.left
    const y = e.clientY - rect.top

    // Calculate zoom factor (negative deltaY = zoom in)
    const zoomFactor = e.deltaY < 0 ? 1.1 : 0.9
    viewport.zoomAt({ x, y }, zoomFactor)
    renderer.applyViewport(viewport)
    onViewportUpdate?.()
  }, [canvasElement, viewport, renderer, onViewportUpdate])

  // Handle mouse down
  const handleMouseDown = useCallback((e: MouseEvent) => {
    if (!canvasElement) return

    // Middle mouse button or shift+left click for panning
    if (e.button === 1 || (e.button === 0 && e.shiftKey)) {
      e.preventDefault()
      isPanningRef.current = true
      lastPanPosRef.current = { x: e.clientX, y: e.clientY }
      canvasElement.style.cursor = 'grabbing'
      return
    }

    // Left mouse button for selection/moving
    if (e.button === 0) {
      e.preventDefault()

      // Check if clicking on selected shape (start move) or empty space (start selection)
      const pos = mousePosition$.value
      if (pos && selectedIds.size > 0) {
        // Check if clicking on a selected shape (simplified - in real app would check bounds)
        setIsMoving(true)
      } else {
        // Start selection
        setIsSelecting(true)
      }
    }
  }, [canvasElement, selectedIds, setIsSelecting, setIsMoving])

  // Handle mouse move for panning
  const handleMouseMove = useCallback((e: MouseEvent) => {
    if (!canvasElement) return

    if (isPanningRef.current && lastPanPosRef.current) {
      e.preventDefault()
      const dx = e.clientX - lastPanPosRef.current.x
      const dy = e.clientY - lastPanPosRef.current.y

      // Update last position immediately
      lastPanPosRef.current = { x: e.clientX, y: e.clientY }

      // Accumulate delta
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
              // Reset accumulated delta
              pendingPanDeltaRef.current = { dx: 0, dy: 0 }
            }
          }
          pendingPanUpdateRef.current = false
        })
      }
    }
  }, [canvasElement, viewport, renderer, onViewportUpdate])

  // Handle mouse up
  const handleMouseUp = useCallback(() => {
    const canvas = canvasElement
    if (isPanningRef.current) {
      isPanningRef.current = false
      lastPanPosRef.current = null
      // Apply any pending pan delta before finishing
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
  }, [canvasElement, viewport, renderer, setIsSelecting, setIsMoving, onViewportUpdate])

  // Handle mouse enter to show grab cursor
  const handleMouseEnter = useCallback(() => {
    const canvas = canvasElement
    if (canvas && !isPanningRef.current) {
      canvas.style.cursor = 'grab'
    }
  }, [canvasElement])

  // Handle mouse leave to reset cursor
  const handleMouseLeave = useCallback(() => {
    const canvas = canvasElement
    if (canvas && !isPanningRef.current) {
      canvas.style.cursor = 'default'
    }
  }, [canvasElement])

  // Handle keyboard for panning and zooming
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (!viewport || !renderer) return

    // Arrow keys for panning (step in screen pixels)
    if (e.code.startsWith('Arrow')) {
      e.preventDefault()
      const step = 20
      if (e.code === 'ArrowLeft') viewport.pan(step, 0)
      if (e.code === 'ArrowRight') viewport.pan(-step, 0)
      if (e.code === 'ArrowUp') viewport.pan(0, step)
      if (e.code === 'ArrowDown') viewport.pan(0, -step)
      renderer.applyViewport(viewport)
      onViewportUpdate?.()
    }

    // +/- keys for zooming
    if (e.code === 'Equal' || e.code === 'NumpadAdd') {
      e.preventDefault()
      if (canvasElement) {
        const rect = canvasElement.getBoundingClientRect()
        viewport.zoomAt({ x: rect.width / 2, y: rect.height / 2 }, 1.1)
        renderer.applyViewport(viewport)
        onViewportUpdate?.()
      }
    }
    if (e.code === 'Minus' || e.code === 'NumpadSubtract') {
      e.preventDefault()
      if (canvasElement) {
        const rect = canvasElement.getBoundingClientRect()
        viewport.zoomAt({ x: rect.width / 2, y: rect.height / 2 }, 0.9)
        renderer.applyViewport(viewport)
        onViewportUpdate?.()
      }
    }

    // Reset viewport with '0' key
    if (e.code === 'Digit0' || e.code === 'Numpad0') {
      e.preventDefault()
      viewport.reset()
      renderer.applyViewport(viewport)
      onViewportUpdate?.()
    }
  }, [canvasElement, viewport, renderer, onViewportUpdate])

  // Set up event listeners
  useEffect(() => {
    const canvas = canvasElement
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
  }, [canvasElement, handleWheel, handleMouseDown, handleMouseEnter, handleMouseLeave, handleMouseMove, handleMouseUp, handleKeyDown])
}

