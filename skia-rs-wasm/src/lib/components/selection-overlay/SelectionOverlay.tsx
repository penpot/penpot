/**
 * SVG overlay for selection bounds, resize handles, and area marquee.
 * Reads wasmSelectionRect from store (updated whenever modifiers or selection change).
 */

import type { RefObject } from 'react'
import { useCallback, useMemo } from 'react'
import { useWorkspaceStore } from '../../renderer/store/workspace-store'
import { useSnapshot } from 'valtio'
import { docProxy } from '../../renderer/store/doc-proxy'
import { mousePosition$ } from '../../renderer/streams'
import type { ResizeHandlePosition } from '../../renderer/types'
import { HANDLE_SIZE_WORLD, MIN_SELRECT_SIDE_SCREEN, getResizeCursor, getRotationCursor, matrixHasHalfFlip, matrixToRotationDeg, SELECTION_OVERLAY_GLOW } from './constants'
import { SelectionRect } from './SelectionRect'
import { ResizeHandles } from './ResizeHandles'
import { CornerHandles } from './CornerHandles'
import { MoveHitArea } from './MoveHitArea'
import { RotationHitArea } from './RotationHitArea'
import { AreaMarquee } from './AreaMarquee'
import { GradientOverlay } from './GradientOverlay'
import { getSelectionWorldCorners } from './world-corners'
import { isLinearGradient, isRadialGradient, isAngularGradient, MAX_GRADIENT_STOPS } from '../../renderer/api/constants'
import type { Fill } from 'penpot-exporter/types'

export interface SelectionOverlayProps {
  canvasSize: { width: number; height: number }
  canvasRef: RefObject<HTMLCanvasElement | null>
}

export function SelectionOverlay({ canvasSize, canvasRef }: SelectionOverlayProps) {
  const doc = useSnapshot(docProxy)
  const selectedIds = useMemo(() => new Set(doc.selectedIds), [doc.selectedIds])
  const wasmSelectionRect = useWorkspaceStore((state) => state.wasmSelectionRect)
  const viewport = useWorkspaceStore((state) => state.viewport)
  const zoom = useWorkspaceStore((state) => state.viewport?.zoom ?? 1)
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

  const safeZoom = Number.isFinite(zoom) && zoom > 0 ? zoom : 1
  const hasFiniteSelectionRect =
    wasmSelectionRect != null &&
    Number.isFinite(wasmSelectionRect.width) &&
    Number.isFinite(wasmSelectionRect.height) &&
    Number.isFinite(wasmSelectionRect.center.x) &&
    Number.isFinite(wasmSelectionRect.center.y) &&
    Number.isFinite(wasmSelectionRect.transform.a) &&
    Number.isFinite(wasmSelectionRect.transform.b) &&
    Number.isFinite(wasmSelectionRect.transform.c) &&
    Number.isFinite(wasmSelectionRect.transform.d)
  /** Show bounds during move/resize/rotate — `wasmSelectionRect` is refreshed each RAF (Penpot-style temp selrect). Handles stay off while moving to avoid hit-target churn. */
  const showSelectionRect = selectedIds.size > 0 && hasFiniteSelectionRect && viewport != null
  const showHandles = selectedIds.size >= 1 && hasFiniteSelectionRect && viewport != null && !isMoving

  const hitSize = HANDLE_SIZE_WORLD / safeZoom

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

  const shapeDrawPreview = useWorkspaceStore((state) => state.shapeDrawPreview)
  const isDrawingShape = useWorkspaceStore((state) => state.isDrawingShape)
  const showShapeDrawPreview =
    isDrawingShape &&
    shapeDrawPreview != null &&
    viewport != null &&
    Number.isFinite(viewport.zoom) &&
    viewport.zoom > 0
  const shapeDrawWorld =
    showShapeDrawPreview && viewport && shapeDrawPreview
      ? {
        x: viewport.panX + (shapeDrawPreview.x ?? 0) / viewport.zoom,
        y: viewport.panY + (shapeDrawPreview.y ?? 0) / viewport.zoom,
        width: (shapeDrawPreview.width ?? 0) / viewport.zoom,
        height: (shapeDrawPreview.height ?? 0) / viewport.zoom,
      }
      : null

  const showAreaMarquee =
    isSelecting &&
    selectionRect != null &&
    viewport != null &&
    Number.isFinite(viewport.zoom) &&
    viewport.zoom > 0
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
    viewport && canvasSize.width > 0 && canvasSize.height > 0 && Number.isFinite(viewport.zoom) && viewport.zoom > 0
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

  const thresholdTinyWorld = MIN_SELRECT_SIDE_SCREEN / safeZoom
  const showCornerHandles =
    wasmSelectionRect != null &&
    wasmSelectionRect.width > thresholdTinyWorld &&
    wasmSelectionRect.height > thresholdTinyWorld

  const gradientFill = useMemo(() => {
    if (selectedIds.size !== 1) return null
    const singleId = Array.from(selectedIds)[0]
    const page = doc.currentPageId ? doc.pageMap.get(doc.currentPageId) : undefined
    const fills = singleId ? page?.objects[singleId]?.fills : undefined
    if (!fills?.length) return null
    return fills.find((f: Fill) => isLinearGradient(f) || isRadialGradient(f) || isAngularGradient(f)) ?? null
  }, [doc, selectedIds])

  const gradientForOverlay =
    gradientFill?.fillColorGradient != null
      ? { ...gradientFill.fillColorGradient, stops: gradientFill.fillColorGradient.stops?.slice(0, MAX_GRADIENT_STOPS) ?? [] }
      : null

  return (
    <svg
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
      <defs>
        <filter id="selection-line-glow" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur in="SourceGraphic" stdDeviation="1.5" result="blur" />
          <feFlood floodColor={SELECTION_OVERLAY_GLOW} result="flood" />
          <feComposite in="flood" in2="blur" operator="in" result="coloredBlur" />
          <feMerge>
            <feMergeNode in="coloredBlur" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>
      {showSelectionRect && rect != null && (
        <>
          <g transform={transformStr}>
            <SelectionRect bounds={rect} skipTransform zoom={zoom} />
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
                  zoom={safeZoom}
                  rotationDeg={rotationDeg}
                  halfFlip={halfFlip}
                  overrideCursor={overrideCursor}
                  onPointerDown={onRotationPointerDown}
                />
              </>
            )}
          </g>
          {showHandles && wasmSelectionRect != null && gradientForOverlay != null && (
            <g style={{ filter: 'url(#selection-line-glow)' }}>
              <GradientOverlay
                wasmSelectionRect={wasmSelectionRect}
                gradient={gradientForOverlay}
                zoom={safeZoom}
              />
            </g>
          )}
          {showHandles && showCornerHandles && worldCorners != null && (
            <CornerHandles
              worldCorners={worldCorners}
              zoom={safeZoom}
              rotationDeg={rotationDeg}
              halfFlip={halfFlip}
              overrideCursor={overrideCursor}
              onResizeHandlePointerDown={onResizeHandlePointerDown}
            />
          )}
        </>
      )}
      {showShapeDrawPreview && shapeDrawWorld && (
        <AreaMarquee world={shapeDrawWorld} zoom={safeZoom} />
      )}
      {showAreaMarquee && areaMarqueeWorld && (
        <AreaMarquee world={areaMarqueeWorld} zoom={safeZoom} />
      )}
    </svg>
  )
}
