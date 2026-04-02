/**
 * SVG overlay for selection bounds, resize handles, and area marquee.
 * Selection outline: always-mounted `<g>` + `<rect>` driven by Preact `effect()` (sync with signals).
 * `viewBox` is updated imperatively from `viewportSignal`. Corner squares: imperative `effect()`.
 * Other handles/marquees stay React-driven (cold path).
 */

import type { RefObject } from 'react'
import { useLayoutEffect, useMemo, useRef } from 'react'
import { useSelector } from '@xstate/react'
import { useCanvasActor } from '../../renderer/machine/canvas-actor-context'
import { useSnapshot } from 'valtio'
import { docProxy } from '../../renderer/store/doc-proxy'
import { pointerPos, viewport as viewportSignal } from '../../renderer/signals/pointer'
import {
  selectionCornerHandlesVisible,
  selectionRect as selectionRectSignal,
  shapeDrawPreview as shapeDrawPreviewSignal,
  wasmSelectionRect as wasmSelectionRectSignal,
} from '../../renderer/signals/selection'
import { useSignalCoalesced } from '../../renderer/signals/use-signal-coalesced'
import {
  HANDLE_FILL,
  HANDLE_SIZE_WORLD,
  HANDLE_STROKE,
  MIN_SELRECT_SIDE_SCREEN,
  SELECTION_STROKE,
  SELECTION_OVERLAY_GLOW,
  getResizeCursor,
  getRotationCursor,
  matrixHasHalfFlip,
  matrixToRotationDeg,
} from './constants'
import { ResizeHandles } from './ResizeHandles'
import { MoveHitArea } from './MoveHitArea'
import { RotationHitArea } from './RotationHitArea'
import { AreaMarquee } from './AreaMarquee'
import { GradientOverlay } from './GradientOverlay'
import { finiteSelectionOverlayRect } from './finite-selection-overlay-rect'
import { screenRectToWorld } from './screen-rect-to-world'
import { useViewBoxSync } from './useViewBoxSync'
import { useImperativeSelectionRect } from './useImperativeSelectionRect'
import {
  CORNER_HANDLE_POSITIONS,
  useImperativeCornerHandles,
  type CornerPointerRef,
  type CornerRectRefsTuple,
} from './useImperativeCornerHandles'
import { usePointerDownFactory } from './usePointerDownFactory'
import { useGradientFill } from './useGradientFill'

export interface SelectionOverlayProps {
  canvasSize: { width: number; height: number }
  canvasRef: RefObject<HTMLCanvasElement | null>
}

export function SelectionOverlay({ canvasSize, canvasRef }: SelectionOverlayProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const hotGRef = useRef<SVGGElement>(null)
  const selRectRef = useRef<SVGRectElement>(null)
  const cornerHandlesGRef = useRef<SVGGElement>(null)
  const cornerRectRefs = useRef<CornerRectRefsTuple>([null, null, null, null])
  const cornerOverrideCursorRef = useRef<string | null>(null)
  const cornerPointerRef = useRef<CornerPointerRef | null>(null)

  useViewBoxSync(svgRef, canvasSize)
  useImperativeSelectionRect(hotGRef, selRectRef)
  useImperativeCornerHandles(cornerHandlesGRef, cornerRectRefs, cornerOverrideCursorRef, cornerPointerRef)

  const canvasActor = useCanvasActor()
  const doc = useSnapshot(docProxy)
  const selectedIds = useMemo(() => new Set(doc.selectedIds), [doc.selectedIds])
  const wasmSelectionRect = useSignalCoalesced(wasmSelectionRectSignal)
  const viewport = useSignalCoalesced(viewportSignal)
  const selectionRect = useSignalCoalesced(selectionRectSignal)
  const isSelecting = useSelector(canvasActor, (s) => s.matches('selecting'))
  const isMoving = useSelector(canvasActor, (s) => s.matches('moving'))
  const isResizing = useSelector(canvasActor, (s) => s.matches('resizing'))
  const resizeHandle = useSelector(canvasActor, (s) => s.context.resizeHandle)
  const isRotating = useSelector(canvasActor, (s) => s.matches('rotating'))
  const rotationCorner = useSelector(canvasActor, (s) => s.context.rotationCorner)

  const rawZoom = viewport?.zoom ?? 1
  const safeZoom = Number.isFinite(rawZoom) && rawZoom > 0 ? rawZoom : 1
  const hasFiniteSelectionRect = finiteSelectionOverlayRect(wasmSelectionRect)
  const showHandles = selectedIds.size >= 1 && hasFiniteSelectionRect && viewport != null && !isMoving

  const hitSize = HANDLE_SIZE_WORLD / safeZoom

  const { onSelectionRectPointerDown, onResizeHandlePointerDown, onRotationPointerDown, onGradientHandlePointerDown } =
    usePointerDownFactory(canvasRef, canvasActor)

  const shapeDrawPreview = useSignalCoalesced(shapeDrawPreviewSignal)
  const isDrawingShape = useSelector(canvasActor, (s) => s.matches('drawingShape'))
  const shapeDrawWorld =
    isDrawingShape &&
      shapeDrawPreview != null &&
      viewport != null &&
      Number.isFinite(viewport.zoom) &&
      viewport.zoom > 0
      ? screenRectToWorld(viewport, shapeDrawPreview)
      : null

  const areaMarqueeWorld =
    isSelecting &&
      selectionRect != null &&
      viewport != null &&
      Number.isFinite(viewport.zoom) &&
      viewport.zoom > 0
      ? screenRectToWorld(viewport, selectionRect)
      : null

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

  useLayoutEffect(() => {
    cornerOverrideCursorRef.current = overrideCursor
    cornerPointerRef.current = {
      canvasRef,
      sendCornerDown(position, screenPos) {
        pointerPos.value = screenPos
        canvasActor.send({ type: 'POINTER_DOWN_ON_CORNER', handle: position, position: screenPos })
      },
    }
  }, [overrideCursor, canvasRef, canvasActor])

  useLayoutEffect(() => {
    const g = cornerHandlesGRef.current
    const rects = cornerRectRefs.current
    if (!g || g.style.display === 'none') return
    const sel = wasmSelectionRectSignal.peek()
    if (!finiteSelectionOverlayRect(sel)) return
    const rd = matrixToRotationDeg(sel.transform)
    const hf = matrixHasHalfFlip(sel.transform)
    for (let i = 0; i < 4; i++) {
      const el = rects[i]
      if (el) el.style.cursor = overrideCursor ?? getResizeCursor(CORNER_HANDLE_POSITIONS[i], rd, hf)
    }
  }, [overrideCursor, rotationDeg, halfFlip])

  const thresholdTinyWorld = MIN_SELRECT_SIDE_SCREEN / safeZoom
  const showCornerHandles =
    wasmSelectionRect != null &&
    wasmSelectionRect.width > thresholdTinyWorld &&
    wasmSelectionRect.height > thresholdTinyWorld

  useLayoutEffect(() => {
    selectionCornerHandlesVisible.value = showHandles && showCornerHandles
  }, [showHandles, showCornerHandles])

  const gradientForOverlay = useGradientFill()

  return (
    <svg
      ref={svgRef}
      aria-hidden
      style={{
        position: 'absolute',
        left: 0,
        top: 0,
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
      }}
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
      <g ref={hotGRef} style={{ display: 'none' }}>
        <rect
          ref={selRectRef}
          fill="none"
          stroke={SELECTION_STROKE}
          style={{ pointerEvents: 'none' }}
        />
      </g>
      <g ref={cornerHandlesGRef} style={{ pointerEvents: 'auto', display: 'none' }}>
        <rect
          ref={(el) => {
            cornerRectRefs.current[0] = el
          }}
          data-handle="top-left"
          fill={HANDLE_FILL}
          stroke={HANDLE_STROKE}
          style={{ pointerEvents: 'auto' }}
        />
        <rect
          ref={(el) => {
            cornerRectRefs.current[1] = el
          }}
          data-handle="top-right"
          fill={HANDLE_FILL}
          stroke={HANDLE_STROKE}
          style={{ pointerEvents: 'auto' }}
        />
        <rect
          ref={(el) => {
            cornerRectRefs.current[2] = el
          }}
          data-handle="bottom-right"
          fill={HANDLE_FILL}
          stroke={HANDLE_STROKE}
          style={{ pointerEvents: 'auto' }}
        />
        <rect
          ref={(el) => {
            cornerRectRefs.current[3] = el
          }}
          data-handle="bottom-left"
          fill={HANDLE_FILL}
          stroke={HANDLE_STROKE}
          style={{ pointerEvents: 'auto' }}
        />
      </g>
      {showHandles && rect != null && (
        <>
          <g transform={transformStr}>
            <MoveHitArea
              bounds={rect}
              hitSize={hitSize}
              overrideCursor={overrideCursor}
              onPointerDown={onSelectionRectPointerDown}
            />
            <ResizeHandles
              effectiveBounds={rect}
              zoom={safeZoom}
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
          </g>
          {gradientForOverlay != null && (
            <g style={{ filter: 'url(#selection-line-glow)' }}>
              <GradientOverlay wasmSelectionRect={wasmSelectionRect} gradient={gradientForOverlay} zoom={safeZoom} onHandlePointerDown={onGradientHandlePointerDown} />
            </g>
          )}
        </>
      )}
      {shapeDrawWorld && <AreaMarquee world={shapeDrawWorld} zoom={safeZoom} />}
      {areaMarqueeWorld && <AreaMarquee world={areaMarqueeWorld} zoom={safeZoom} />}
    </svg>
  )
}
