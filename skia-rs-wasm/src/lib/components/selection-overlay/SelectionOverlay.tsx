/**
 * SVG overlay for selection bounds, resize handles, and area marquee.
 * Reads wasmSelectionRect from store (updated whenever modifiers or selection change).
 */

import type { RefObject } from 'react'
import { useCallback, useMemo } from 'react'
import { useWorkspaceStore } from '../../renderer/store/workspace-store'
import { mousePosition$ } from '../../renderer/streams'
import type { ResizeHandlePosition } from '../../renderer/types'
import { HANDLE_SIZE_WORLD, getResizeCursor, getRotationCursor, matrixHasHalfFlip, matrixToRotationDeg } from './constants'
import { SelectionRect } from './SelectionRect'
import { ResizeHandles } from './ResizeHandles'
import { CornerHandles } from './CornerHandles'
import { MoveHitArea } from './MoveHitArea'
import { RotationHitArea } from './RotationHitArea'
import { AreaMarquee } from './AreaMarquee'
import { getSelectionWorldCorners } from './world-corners'

export interface SelectionOverlayProps {
  canvasSize: { width: number; height: number }
  canvasRef: RefObject<HTMLCanvasElement | null>
}

export function SelectionOverlay({ canvasSize, canvasRef }: SelectionOverlayProps) {
  const selectedIds = useWorkspaceStore((state) => state.selectedIds)
  const wasmSelectionRect = useWorkspaceStore((state) => state.wasmSelectionRect)
  const viewport = useWorkspaceStore((state) => state.viewport)
  const viewportVersion = useWorkspaceStore((state) => state.viewportVersion)
  const zoom = useWorkspaceStore((state) => (state.viewportVersion, state.viewport?.zoom ?? 1))
  const isSelecting = useWorkspaceStore((state) => state.isSelecting)
  const selectionRect = useWorkspaceStore((state) => state.selectionRect)
  const isMoving = useWorkspaceStore((state) => state.isMoving)
  const setIsMoving = useWorkspaceStore((state) => state.setIsMoving)
  const isResizing = useWorkspaceStore((state) => state.isResizing)
  const resizeHandle = useWorkspaceStore((state) => state.resizeHandle)
  const setIsResizing = useWorkspaceStore((state) => state.setIsResizing)
  const setResizeHandle = useWorkspaceStore((state) => state.setResizeHandle)
  const isRotating = useWorkspaceStore((state) => state.isRotating)
  const rotationCorner = useWorkspaceStore((state) => state.rotationCorner)
  const setIsRotating = useWorkspaceStore((state) => state.setIsRotating)
  const setRotationCorner = useWorkspaceStore((state) => state.setRotationCorner)

  const showSelectionRect = selectedIds.size > 0 && wasmSelectionRect != null && viewport != null && !isMoving
  const showHandles = selectedIds.size >= 1 && wasmSelectionRect != null && viewport != null && !isMoving

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
        setIsMoving(true)
      }
      const target = e.currentTarget
      if (target instanceof Element) target.setPointerCapture(e.pointerId)
    },
    [screenPositionFromEvent, setIsMoving]
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

  const onRotationPointerDown = useCallback(
    (e: React.PointerEvent, position: ResizeHandlePosition) => {
      if (e.button !== 0) return
      e.preventDefault()
      e.stopPropagation()
      const pos = screenPositionFromEvent(e)
      if (pos) {
        mousePosition$.next(pos)
        setRotationCorner(position)
        setIsRotating(true)
      }
      const target = e.currentTarget
      if (target instanceof Element) target.setPointerCapture(e.pointerId)
    },
    [screenPositionFromEvent, setRotationCorner, setIsRotating]
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

  // Rect in local space (center at origin); transform = translate(center) then rotation/scale (WASM returns e,f=0).
  const rect =
    wasmSelectionRect != null
      ? {
        x: -wasmSelectionRect.width / 2,
        y: -wasmSelectionRect.height / 2,
        width: wasmSelectionRect.width,
        height: wasmSelectionRect.height,
      }
      : null
  const transformStr =
    wasmSelectionRect != null
      ? `translate(${wasmSelectionRect.center.x},${wasmSelectionRect.center.y}) matrix(${wasmSelectionRect.transform.a},${wasmSelectionRect.transform.b},${wasmSelectionRect.transform.c},${wasmSelectionRect.transform.d},0,0)`
      : ''
  const rotationDeg = wasmSelectionRect != null ? matrixToRotationDeg(wasmSelectionRect.transform) : undefined
  const halfFlip = wasmSelectionRect != null ? matrixHasHalfFlip(wasmSelectionRect.transform) : false
  const overrideCursor =
    isResizing && resizeHandle
      ? getResizeCursor(resizeHandle, rotationDeg, halfFlip)
      : isRotating && rotationCorner
        ? getRotationCursor(rotationCorner, rotationDeg, halfFlip)
        : null

  const worldCorners = useMemo(
    () => (wasmSelectionRect != null ? getSelectionWorldCorners(wasmSelectionRect) : null),
    [wasmSelectionRect]
  )

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
      {showSelectionRect && rect != null && (
        <>
          <g transform={transformStr}>
            <SelectionRect bounds={rect} skipTransform />
            {showHandles && (
              <>
                <MoveHitArea
                  bounds={rect}
                  hitSize={hitSize}
                  overrideCursor={overrideCursor}
                  onPointerDown={onSelectionRectPointerDown}
                />
                <ResizeHandles
                  effectiveBounds={rect}
                  zoom={zoom}
                  skipCorners
                  rotationDeg={rotationDeg}
                  halfFlip={halfFlip}
                  overrideCursor={overrideCursor}
                  onResizeHandlePointerDown={onResizeHandlePointerDown}
                />
                <RotationHitArea
                  bounds={rect}
                  zoom={zoom}
                  rotationDeg={rotationDeg}
                  halfFlip={halfFlip}
                  overrideCursor={overrideCursor}
                  onPointerDown={onRotationPointerDown}
                />
              </>
            )}
          </g>
          {showHandles && worldCorners != null && (
            <CornerHandles
              worldCorners={worldCorners}
              zoom={zoom}
              rotationDeg={rotationDeg}
              halfFlip={halfFlip}
              overrideCursor={overrideCursor}
              onResizeHandlePointerDown={onResizeHandlePointerDown}
            />
          )}
        </>
      )}
      {showAreaMarquee && areaMarqueeWorld && (
        <AreaMarquee world={areaMarqueeWorld} zoom={zoom} />
      )}
    </svg>
  )
}
