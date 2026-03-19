/**
 * Read-only gradient overlay: line, endpoints, angular center/circle, and stop positions.
 * Drawn in viewport space (same as frontend): local geometry is converted to viewport
 * and all sizes use zoom only (no shape scale factor).
 */

import { useMemo } from 'react'
import type { Gradient, Matrix } from 'penpot-exporter/types'
import type { SelectionRectResult } from '../../renderer/types'
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
 * Affine transform of ellipse: compute perpendicular semi-axes and rotation in viewport.
 * Given local ellipse (radiusX, radiusY, angle0) and 2x2 linear part {a,b,c,d}, returns
 * { rx, ry, rotationRad } for the transformed ellipse (exact under shear).
 */
function ellipseAffineTransform(
  radiusX: number,
  radiusY: number,
  angle0: number,
  transform: Matrix
): { rx: number; ry: number; rotationRad: number } {
  const { a, b, c, d } = transform
  const cos0 = Math.cos(angle0)
  const sin0 = Math.sin(angle0)
  const V1 = { x: radiusX * cos0, y: radiusX * sin0 }
  const V2 = { x: radiusY * -sin0, y: radiusY * cos0 }
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
}: GradientOverlayProps) {
  const { width: selW, height: selH, center, transform } = wasmSelectionRect

  const toViewport = useMemo(
    () => (local: { x: number; y: number }) =>
      localToViewport(local, center, transform),
    [center, transform]
  )

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

    return {
      localFrom,
      localTo,
      stops,
    }
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
    const angle0 = Math.atan2(
      localAngleZero.y - localCenter.y,
      localAngleZero.x - localCenter.x
    )
    const radiusX = selW * 0.4 * (gradient.width ?? 1)
    const radiusY = selH * 0.4

    const stops =
      gradient.stops?.slice(0, 16).map((stop) => {
        const angle = angle0 + stop.offset * 2 * Math.PI
        const lx = radiusX * Math.cos(angle)
        const ly = radiusY * Math.sin(angle)
        const x = localCenter.x + lx * Math.cos(angle0) - ly * Math.sin(angle0)
        const y = localCenter.y + lx * Math.sin(angle0) + ly * Math.cos(angle0)
        return { ...stop, local: { x, y } }
      }) ?? []
    const localAngle0OnEllipse = {
      x: localCenter.x + radiusX * Math.cos(angle0),
      y: localCenter.y + radiusX * Math.sin(angle0),
    }
    const localAngle90OnEllipse = {
      x: localCenter.x - radiusY * Math.sin(angle0),
      y: localCenter.y + radiusY * Math.cos(angle0),
    }
    return {
      localCenter,
      radiusX,
      radiusY,
      angle0,
      stops,
      localAngle0OnEllipse,
      localAngle90OnEllipse,
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
    const normLen = Math.hypot(normGradientVec.x, normGradientVec.y) || 1
    const normPerp = {
      x: normGradientVec.y / normLen,
      y: -normGradientVec.x / normLen,
    }
    const normWidthDist = normLen * (gradient.width ?? 1)
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
    return {
      localCenter,
      localTo,
      localWidth,
      stops,
    }
  }, [gradient, selW, selH])

  // Zoom-only sizing (viewport space; no scale factor)
  const sc = GRADIENT_LINE_STROKE_WORLD / 2 / zoom
  const endpointR = GRADIENT_ENDPOINT_RADIUS_WORLD / zoom
  const stopR = GRADIENT_STOP_DOT_RADIUS_WORLD / zoom
  const lineStroke = GRADIENT_LINE_STROKE_WORLD / zoom

  if (gradient.type === 'angular' && angularPoints != null) {
    const {
      localCenter,
      radiusX,
      radiusY,
      angle0,
      localAngle0OnEllipse,
      localAngle90OnEllipse,
    } = angularPoints
    const vpCenter = toViewport(localCenter)
    const vpAngle0 = toViewport(localAngle0OnEllipse)
    const vpAngle90 = toViewport(localAngle90OnEllipse)
    const { rx, ry, rotationRad } = ellipseAffineTransform(
      radiusX,
      radiusY,
      angle0,
      transform
    )
    const ellipseRotationDeg = (rotationRad * 180) / Math.PI

    return (
      <g aria-hidden style={{ pointerEvents: 'none' }}>
        <circle
          cx={vpCenter.x}
          cy={vpCenter.y}
          r={endpointR}
          fill={HANDLE_FILL}
        />
        <polygon
          points={lineSegmentToPolygonPoints(vpCenter, vpAngle0, sc)}
          fill={HANDLE_FILL}
        />
        <polygon
          points={lineSegmentToPolygonPoints(vpCenter, vpAngle90, sc)}
          fill={HANDLE_FILL}
        />
        <circle
          cx={vpAngle0.x}
          cy={vpAngle0.y}
          r={endpointR}
          fill={HANDLE_FILL}
        />
        <circle
          cx={vpAngle90.x}
          cy={vpAngle90.y}
          r={endpointR}
          fill={HANDLE_FILL}
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
        />
        {angularPoints.stops.map((stop, i) => {
          const vp = toViewport(stop.local)
          return (
            <g key={i}>
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
      </g>
    )
  }

  if (gradient.type === 'radial' && radialPoints != null) {
    const { localCenter, localTo, localWidth, stops } = radialPoints
    const vpCenter = toViewport(localCenter)
    const vpTo = toViewport(localTo)
    const vpWidth = toViewport(localWidth)
    return (
      <g aria-hidden style={{ pointerEvents: 'none' }}>
        <polygon
          points={lineSegmentToPolygonPoints(vpCenter, vpTo, sc)}
          fill={HANDLE_FILL}
        />
        <polygon
          points={lineSegmentToPolygonPoints(vpCenter, vpWidth, sc)}
          fill={HANDLE_FILL}
        />
        <circle cx={vpCenter.x} cy={vpCenter.y} r={endpointR} fill={HANDLE_FILL} />
        <circle cx={vpTo.x} cy={vpTo.y} r={endpointR} fill={HANDLE_FILL} />
        <circle cx={vpWidth.x} cy={vpWidth.y} r={endpointR} fill={HANDLE_FILL} />
        {stops.map((stop, i) => {
          const vp = toViewport(stop.local)
          return (
            <g key={i}>
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
      </g>
    )
  }

  // Linear
  const vpFrom = toViewport(points.localFrom)
  const vpTo = toViewport(points.localTo)
  return (
    <g aria-hidden style={{ pointerEvents: 'none' }}>
      <polygon
        points={lineSegmentToPolygonPoints(vpFrom, vpTo, sc)}
        fill={HANDLE_FILL}
      />
      <circle cx={vpFrom.x} cy={vpFrom.y} r={endpointR} fill={HANDLE_FILL} />
      <circle cx={vpTo.x} cy={vpTo.y} r={endpointR} fill={HANDLE_FILL} />
      {points.stops.map((stop, i) => {
        const vp = toViewport(stop.local)
        return (
          <g key={i}>
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
    </g>
  )
}
