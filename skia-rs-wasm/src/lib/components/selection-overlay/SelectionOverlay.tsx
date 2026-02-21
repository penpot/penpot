/**
 * SVG overlay for selection bounds, resize handles, and area marquee.
 * Reads from workspace store; receives canvasSize and canvasRef for viewBox and screen coords.
 */

import type { RefObject } from 'react'
import { useMemo, useCallback } from 'react'
import { useWorkspaceStore } from '../../renderer/store/workspace-store'
import { mousePosition$ } from '../../renderer/streams'
import type { ResizeHandlePosition } from '../../renderer/types'
import { HANDLE_SIZE_WORLD } from './constants'
import { SelectionRect } from './SelectionRect'
import { ResizeHandles } from './ResizeHandles'
import { MoveHitArea } from './MoveHitArea'
import { AreaMarquee } from './AreaMarquee'

export interface SelectionOverlayProps {
  canvasSize: { width: number; height: number }
  canvasRef: RefObject<HTMLCanvasElement | null>
}

export function SelectionOverlay({ canvasSize, canvasRef }: SelectionOverlayProps) {
  const selectedIds = useWorkspaceStore((state) => state.selectedIds)
  const selectionBounds = useWorkspaceStore((state) => state.selectionBounds)
  const viewport = useWorkspaceStore((state) => state.viewport)
  const viewportVersion = useWorkspaceStore((state) => state.viewportVersion)
  const isSelecting = useWorkspaceStore((state) => state.isSelecting)
  const selectionRect = useWorkspaceStore((state) => state.selectionRect)
  const isMoving = useWorkspaceStore((state) => state.isMoving)
  const movePreviewDelta = useWorkspaceStore((state) => state.movePreviewDelta)
  const setIsMoving = useWorkspaceStore((state) => state.setIsMoving)
  const setIsResizing = useWorkspaceStore((state) => state.setIsResizing)
  const setResizeHandle = useWorkspaceStore((state) => state.setResizeHandle)
  const setResizePreviewBounds = useWorkspaceStore((state) => state.setResizePreviewBounds)
  const isResizing = useWorkspaceStore((state) => state.isResizing)
  const resizePreviewBounds = useWorkspaceStore((state) => state.resizePreviewBounds)

  const showSelectionRect = selectedIds.size > 0 && (selectionBounds || resizePreviewBounds) && viewport && !isMoving
  const effectiveBounds = useMemo(
    () =>
      showSelectionRect &&
      (isResizing && resizePreviewBounds
        ? resizePreviewBounds
        : selectionBounds
          ? isMoving && movePreviewDelta
            ? {
              x: selectionBounds.x + movePreviewDelta.x,
              y: selectionBounds.y + movePreviewDelta.y,
              width: selectionBounds.width,
              height: selectionBounds.height,
            }
            : selectionBounds
          : null),
    [
      showSelectionRect,
      isResizing,
      resizePreviewBounds,
      selectionBounds,
      isMoving,
      movePreviewDelta,
    ]
  )

  const singleSelection = selectedIds.size === 1
  const showHandles = singleSelection && effectiveBounds && viewport && !isMoving

  const zoom = viewport?.zoom ?? 1
  const hitSize = (HANDLE_SIZE_WORLD / zoom) / 2 * 2

  const screenPositionFromEvent = useCallback(
    (e: React.PointerEvent): { x: number; y: number } | null => {
      const canvas = canvasRef.current
      if (!canvas) return null
      const rect = canvas.getBoundingClientRect()
      return {
        x: e.clientX - rect.left,
        y: e.clientY - rect.top,
      }
    },
    [canvasRef]
  )

  const onSelectionRectPointerDown = useCallback(
    (e: React.PointerEvent) => {
      if (e.button !== 0) return
      e.preventDefault()
      const pos = screenPositionFromEvent(e)
      if (pos) {
        mousePosition$.next(pos)
        setResizePreviewBounds(null)
        setIsMoving(true)
      }
      const target = e.currentTarget
      if (target instanceof Element) target.setPointerCapture(e.pointerId)
    },
    [screenPositionFromEvent, setResizePreviewBounds, setIsMoving]
  )

  const onResizeHandlePointerDown = useCallback(
    (e: React.PointerEvent, position: ResizeHandlePosition) => {
      if (e.button !== 0) return
      e.preventDefault()
      e.stopPropagation()
      const pos = screenPositionFromEvent(e)
      if (pos) {
        mousePosition$.next(pos)
        setIsResizing(true)
        setResizeHandle(position)
      }
      const target = e.currentTarget
      if (target instanceof Element) target.setPointerCapture(e.pointerId)
    },
    [screenPositionFromEvent, setIsResizing, setResizeHandle]
  )

  const showAreaMarquee = isSelecting && selectionRect != null && viewport != null
  const areaMarqueeWorld =
    showAreaMarquee && viewport && selectionRect
      ? {
        x: viewport.panX + (selectionRect.x ?? 0) / viewport.zoom,
        y: viewport.panY + (selectionRect.y ?? 0) / viewport.zoom,
        width: (selectionRect.width ?? 0) / viewport.zoom,
        height: (selectionRect.height ?? 0) / viewport.zoom,
      }
      : null
  const viewBox =
    viewport && canvasSize.width > 0 && canvasSize.height > 0
      ? `${viewport.panX} ${viewport.panY} ${canvasSize.width / viewport.zoom} ${canvasSize.height / viewport.zoom}`
      : '0 0 100 100'

  return (
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
        <SelectionRect bounds={effectiveBounds} zoom={zoom} />
      )}
      {showHandles && effectiveBounds && (
        <ResizeHandles
          effectiveBounds={effectiveBounds}
          zoom={zoom}
          onResizeHandlePointerDown={onResizeHandlePointerDown}
        />
      )}
      {showHandles && effectiveBounds && (
        <MoveHitArea
          bounds={effectiveBounds}
          hitSize={hitSize}
          onPointerDown={onSelectionRectPointerDown}
        />
      )}
      {showAreaMarquee && areaMarqueeWorld && (
        <AreaMarquee world={areaMarqueeWorld} zoom={zoom} />
      )}
    </svg>
  )
}
