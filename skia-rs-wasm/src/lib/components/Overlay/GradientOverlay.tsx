/**
 * Interactive gradient overlay: line, endpoints, angular center/circle, and stop positions.
 * Drawn in viewport space (same as frontend): local geometry is converted to viewport
 * and all sizes use zoom only (no shape scale factor).
 * Endpoint circles are draggable — pointer-down events are forwarded to the canvas machine
 * via `onHandlePointerDown`, which routes them through the XState → RxJS gradient handler.
 */

import { useMemo } from 'react'
import type { Gradient, Matrix } from 'penpot-exporter/types'
import type { SelectionRectResult } from '../../renderer/types'
import type { GradientHandleKind } from '../../renderer/handlers/gradient'
import {
  HANDLE_FILL,
  GRADIENT_LINE_STROKE_WORLD,
  GRADIENT_ENDPOINT_RADIUS_WORLD,
  GRADIENT_STOP_DOT_RADIUS_WORLD,
} from './constants'

export interface GradientOverlayProps {
  wasmSelectionRect: SelectionRectResult
  gradient: Gradient
  zoom: number
  onHandlePointerDown: (e: React.PointerEvent, kind: GradientHandleKind) => void
}

function localToViewport(
  local: { x: number; y: number },
  center: { x: number; y: number },
  transform: Matrix
): { x: number; y: number } {
  const { a, b, c, d } = transform
  return {
    x: center.x + a * local.x + c * local.y,
    y: center.y + b * local.x + d * local.y,
  }
}

/**
 * Decompose a parametric ellipse P(t) = center + cos(t)*V1 + sin(t)*V2,
 * after applying `transform`, into SVG-compatible (rx, ry, rotation).
 * V1 and V2 need not be perpendicular — the decomposition handles any pair.
 */
function ellipseAffineTransform(
  V1: { x: number; y: number },
  V2: { x: number; y: number },
  transform: Matrix
): { rx: number; ry: number; rotationRad: number } {
  const { a, b, c, d } = transform
  const W1 = { x: a * V1.x + c * V1.y, y: b * V1.x + d * V1.y }
  const W2 = { x: a * V2.x + c * V2.y, y: b * V2.x + d * V2.y }
  const w1w2 = W1.x * W2.x + W1.y * W2.y
  const w11 = W1.x * W1.x + W1.y * W1.y
  const w22 = W2.x * W2.x + W2.y * W2.y
  const denom = w11 - w22
  const t0 =
    denom === 0 && w1w2 === 0 ? 0 : 0.5 * Math.atan2(2 * w1w2, denom)
  const cosT = Math.cos(t0)
  const sinT = Math.sin(t0)
  const U1 = {
    x: W1.x * cosT + W2.x * sinT,
    y: W1.y * cosT + W2.y * sinT,
  }
  const U2 = {
    x: -W1.x * sinT + W2.x * cosT,
    y: -W1.y * sinT + W2.y * cosT,
  }
  let rx = Math.hypot(U1.x, U1.y)
  let ry = Math.hypot(U2.x, U2.y)
  const MIN_AXIS = 1e-6
  if (rx < MIN_AXIS) rx = MIN_AXIS
  if (ry < MIN_AXIS) ry = MIN_AXIS
  const rotationRad = Math.atan2(U1.y, U1.x)
  return { rx, ry, rotationRad }
}

/** Right-hand perpendicular: (dx, dy) -> (dy, -dx) normalized. Polygon: from-sc*perp, from+sc*perp, to+sc*perp, to-sc*perp. */
function lineSegmentToPolygonPoints(
  from: { x: number; y: number },
  to: { x: number; y: number },
  sc: number
): string {
  const dx = to.x - from.x
  const dy = to.y - from.y
  const len = Math.hypot(dx, dy) || 1
  const px = dy / len
  const py = -dx / len
  const p1 = { x: from.x - sc * px, y: from.y - sc * py }
  const p2 = { x: from.x + sc * px, y: from.y + sc * py }
  const p3 = { x: to.x + sc * px, y: to.y + sc * py }
  const p4 = { x: to.x - sc * px, y: to.y - sc * py }
  return `${p1.x},${p1.y} ${p2.x},${p2.y} ${p3.x},${p3.y} ${p4.x},${p4.y}`
}

export function GradientOverlay({
  wasmSelectionRect,
  gradient,
  zoom,
  onHandlePointerDown,
}: GradientOverlayProps) {
  const { width: selW, height: selH, center, transform } = wasmSelectionRect

  const toViewport = useMemo(
    () => (local: { x: number; y: number }) =>
      localToViewport(local, center, transform),
    [center, transform]
  )

  const handleProps = (kind: GradientHandleKind) => ({
    style: { pointerEvents: 'auto' as const, cursor: 'grab' },
    onPointerDown: (e: React.PointerEvent) => onHandlePointerDown(e, kind),
  })

  const points = useMemo(() => {
    const localFrom = {
      x: selW * (gradient.startX - 0.5),
      y: selH * (gradient.startY - 0.5),
    }
    const localTo = {
      x: selW * (gradient.endX - 0.5),
      y: selH * (gradient.endY - 0.5),
    }
    const gradientVec = { x: localTo.x - localFrom.x, y: localTo.y - localFrom.y }
    const stops =
      gradient.stops?.slice(0, 16).map((stop) => {
        const x = localFrom.x + gradientVec.x * stop.offset
        const y = localFrom.y + gradientVec.y * stop.offset
        return { ...stop, local: { x, y } }
      }) ?? []

    return { localFrom, localTo, stops }
  }, [gradient, selW, selH])

  const angularPoints = useMemo(() => {
    if (gradient.type !== 'angular') return null
    const localCenter = {
      x: selW * (gradient.startX - 0.5),
      y: selH * (gradient.startY - 0.5),
    }
    const localAngleZero = {
      x: selW * (gradient.endX - 0.5),
      y: selH * (gradient.endY - 0.5),
    }
    // V1 = vector from center to end dot (gradient direction axis)
    const V1 = {
      x: localAngleZero.x - localCenter.x,
      y: localAngleZero.y - localCenter.y,
    }
    // V2 = vector from center to width dot (perpendicular axis, same approach as radial)
    const normGradientVec = {
      x: gradient.endX - gradient.startX,
      y: gradient.endY - gradient.startY,
    }
    const normLen = Math.hypot(normGradientVec.x, normGradientVec.y)
    const isDegenerate = normLen < 1e-6
    const normPerp = isDegenerate
      ? { x: 1, y: 0 }
      : { x: -normGradientVec.y / normLen, y: normGradientVec.x / normLen }
    const normWidthDist = (isDegenerate ? 1 : normLen) * (gradient.width ?? 1)
    const localWidthPoint = {
      x: localCenter.x + normPerp.x * normWidthDist * selW,
      y: localCenter.y + normPerp.y * normWidthDist * selH,
    }
    const V2 = {
      x: localWidthPoint.x - localCenter.x,
      y: localWidthPoint.y - localCenter.y,
    }

    const stops =
      gradient.stops?.slice(0, 16).map((stop) => {
        const t = stop.offset * 2 * Math.PI
        const x = localCenter.x + Math.cos(t) * V1.x + Math.sin(t) * V2.x
        const y = localCenter.y + Math.cos(t) * V1.y + Math.sin(t) * V2.y
        return { ...stop, local: { x, y } }
      }) ?? []
    return {
      localCenter,
      localAngleZero,
      localWidthPoint,
      V1,
      V2,
      stops,
    }
  }, [gradient, selW, selH])

  const radialPoints = useMemo(() => {
    if (gradient.type !== 'radial') return null
    const localCenter = {
      x: selW * (gradient.startX - 0.5),
      y: selH * (gradient.startY - 0.5),
    }
    const localTo = {
      x: selW * (gradient.endX - 0.5),
      y: selH * (gradient.endY - 0.5),
    }
    const gradientVec = { x: localTo.x - localCenter.x, y: localTo.y - localCenter.y }
    const normGradientVec = {
      x: gradient.endX - gradient.startX,
      y: gradient.endY - gradient.startY,
    }
    const normLen = Math.hypot(normGradientVec.x, normGradientVec.y)
    const isDegenerate = normLen < 1e-6
    // Perpendicular to gradient direction. Fall back to (1, 0) when vector is zero-length
    // so the width handle is still visible and draggable.
    const normPerp = isDegenerate
      ? { x: 1, y: 0 }
      : { x: normGradientVec.y / normLen, y: -normGradientVec.x / normLen }
    const normWidthDist = (isDegenerate ? 1 : normLen) * (gradient.width ?? 1)
    const localWidth = {
      x: localCenter.x + normPerp.x * normWidthDist * selW,
      y: localCenter.y + normPerp.y * normWidthDist * selH,
    }
    const stops =
      gradient.stops?.slice(0, 16).map((stop) => {
        const x = localCenter.x + gradientVec.x * stop.offset
        const y = localCenter.y + gradientVec.y * stop.offset
        return { ...stop, local: { x, y } }
      }) ?? []
    return { localCenter, localTo, localWidth, stops }
  }, [gradient, selW, selH])

  // Zoom-only sizing (viewport space; no scale factor)
  const sc = GRADIENT_LINE_STROKE_WORLD / 2 / zoom
  const endpointR = GRADIENT_ENDPOINT_RADIUS_WORLD / zoom
  const stopR = GRADIENT_STOP_DOT_RADIUS_WORLD / zoom
  const lineStroke = GRADIENT_LINE_STROKE_WORLD / zoom
  // Larger hit area for easier grabbing
  const hitR = Math.max(endpointR * 2, 6 / zoom)

  if (gradient.type === 'angular' && angularPoints != null) {
    const {
      localCenter,
      localAngleZero,
      localWidthPoint,
      V1,
      V2,
    } = angularPoints
    const vpCenter = toViewport(localCenter)
    const vpAngle0 = toViewport(localAngleZero)
    const vpAngle90 = toViewport(localWidthPoint)
    const { rx, ry, rotationRad } = ellipseAffineTransform(
      V1,
      V2,
      transform
    )
    const ellipseRotationDeg = (rotationRad * 180) / Math.PI

    return (
      <g aria-hidden>
        <polygon
          points={lineSegmentToPolygonPoints(vpCenter, vpAngle0, sc)}
          fill={HANDLE_FILL}
          style={{ pointerEvents: 'none' }}
        />
        <polygon
          points={lineSegmentToPolygonPoints(vpCenter, vpAngle90, sc)}
          fill={HANDLE_FILL}
          style={{ pointerEvents: 'none' }}
        />
        <ellipse
          cx={vpCenter.x}
          cy={vpCenter.y}
          rx={rx}
          ry={ry}
          fill="none"
          stroke={HANDLE_FILL}
          strokeWidth={lineStroke}
          transform={`rotate(${ellipseRotationDeg} ${vpCenter.x} ${vpCenter.y})`}
          style={{ pointerEvents: 'none' }}
        />
        {angularPoints.stops.map((stop, i) => {
          const vp = toViewport(stop.local)
          return (
            <g key={i} style={{ pointerEvents: 'none' }}>
              <rect
                x={vp.x + stopR}
                y={vp.y - stopR * 2}
                width={stopR * 4}
                height={stopR * 4}
                fill={stop.color}
                fillOpacity={stop.opacity ?? 1}
                stroke={HANDLE_FILL}
                strokeWidth={lineStroke / 2}
              />
              <circle cx={vp.x} cy={vp.y} r={stopR} fill={HANDLE_FILL} />
            </g>
          )
        })}
        {/* Draggable: center */}
        <circle cx={vpCenter.x} cy={vpCenter.y} r={endpointR} fill={HANDLE_FILL} style={{ pointerEvents: 'none' }} />
        <circle cx={vpCenter.x} cy={vpCenter.y} r={hitR} fill="transparent" {...handleProps('start')} />
        {/* Draggable: angle-0 */}
        <circle cx={vpAngle0.x} cy={vpAngle0.y} r={endpointR} fill={HANDLE_FILL} style={{ pointerEvents: 'none' }} />
        <circle cx={vpAngle0.x} cy={vpAngle0.y} r={hitR} fill="transparent" {...handleProps('end')} />
        {/* Draggable: angle-90 (width) */}
        <circle cx={vpAngle90.x} cy={vpAngle90.y} r={endpointR} fill={HANDLE_FILL} style={{ pointerEvents: 'none' }} />
        <circle cx={vpAngle90.x} cy={vpAngle90.y} r={hitR} fill="transparent" {...handleProps('width')} />
      </g>
    )
  }

  if (gradient.type === 'radial' && radialPoints != null) {
    const { localCenter, localTo, localWidth, stops } = radialPoints
    const vpCenter = toViewport(localCenter)
    const vpTo = toViewport(localTo)
    const vpWidth = toViewport(localWidth)
    return (
      <g aria-hidden>
        <polygon
          points={lineSegmentToPolygonPoints(vpCenter, vpTo, sc)}
          fill={HANDLE_FILL}
          style={{ pointerEvents: 'none' }}
        />
        <polygon
          points={lineSegmentToPolygonPoints(vpCenter, vpWidth, sc)}
          fill={HANDLE_FILL}
          style={{ pointerEvents: 'none' }}
        />
        {stops.map((stop, i) => {
          const vp = toViewport(stop.local)
          return (
            <g key={i} style={{ pointerEvents: 'none' }}>
              <rect
                x={vp.x + stopR}
                y={vp.y - stopR * 2}
                width={stopR * 4}
                height={stopR * 4}
                fill={stop.color}
                fillOpacity={stop.opacity ?? 1}
                stroke={HANDLE_FILL}
                strokeWidth={lineStroke / 2}
              />
              <circle cx={vp.x} cy={vp.y} r={stopR} fill={HANDLE_FILL} />
            </g>
          )
        })}
        {/* Draggable: center */}
        <circle cx={vpCenter.x} cy={vpCenter.y} r={endpointR} fill={HANDLE_FILL} style={{ pointerEvents: 'none' }} />
        <circle cx={vpCenter.x} cy={vpCenter.y} r={hitR} fill="transparent" {...handleProps('start')} />
        {/* Draggable: outer */}
        <circle cx={vpTo.x} cy={vpTo.y} r={endpointR} fill={HANDLE_FILL} style={{ pointerEvents: 'none' }} />
        <circle cx={vpTo.x} cy={vpTo.y} r={hitR} fill="transparent" {...handleProps('end')} />
        {/* Draggable: width */}
        <circle cx={vpWidth.x} cy={vpWidth.y} r={endpointR} fill={HANDLE_FILL} style={{ pointerEvents: 'none' }} />
        <circle cx={vpWidth.x} cy={vpWidth.y} r={hitR} fill="transparent" {...handleProps('width')} />
      </g>
    )
  }

  // Linear
  const vpFrom = toViewport(points.localFrom)
  const vpTo = toViewport(points.localTo)
  return (
    <g aria-hidden>
      <polygon
        points={lineSegmentToPolygonPoints(vpFrom, vpTo, sc)}
        fill={HANDLE_FILL}
        style={{ pointerEvents: 'none' }}
      />
      {points.stops.map((stop, i) => {
        const vp = toViewport(stop.local)
        return (
          <g key={i} style={{ pointerEvents: 'none' }}>
            <rect
              x={vp.x + stopR}
              y={vp.y - stopR * 2}
              width={stopR * 4}
              height={stopR * 4}
              fill={stop.color}
              fillOpacity={stop.opacity ?? 1}
              stroke={HANDLE_FILL}
              strokeWidth={lineStroke / 2}
            />
            <circle cx={vp.x} cy={vp.y} r={stopR} fill={HANDLE_FILL} />
          </g>
        )
      })}
      {/* Draggable: start */}
      <circle cx={vpFrom.x} cy={vpFrom.y} r={endpointR} fill={HANDLE_FILL} style={{ pointerEvents: 'none' }} />
      <circle cx={vpFrom.x} cy={vpFrom.y} r={hitR} fill="transparent" {...handleProps('start')} />
      {/* Draggable: end */}
      <circle cx={vpTo.x} cy={vpTo.y} r={endpointR} fill={HANDLE_FILL} style={{ pointerEvents: 'none' }} />
      <circle cx={vpTo.x} cy={vpTo.y} r={hitR} fill="transparent" {...handleProps('end')} />
    </g>
  )
}
