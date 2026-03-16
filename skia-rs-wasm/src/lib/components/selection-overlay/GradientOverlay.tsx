/**
 * Read-only gradient overlay: line, endpoints, angular center/circle, and stop positions.
 * Drawn in local shape space inside the selection transform group so rotation/scale apply.
 */

import { useMemo } from 'react'
import type { Gradient } from 'penpot-exporter/types'
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

export function GradientOverlay({
  wasmSelectionRect,
  gradient,
  zoom,
}: GradientOverlayProps) {
  const { width: selW, height: selH, transform } = wasmSelectionRect
  const { a, b } = transform
  const scaleFactor = Math.sqrt(a * a + b * b) || 1

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
    const angle0Deg = (angle0 * 180) / Math.PI
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
      angle0Deg,
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

  const lineStroke = GRADIENT_LINE_STROKE_WORLD / zoom / scaleFactor
  const endpointR = GRADIENT_ENDPOINT_RADIUS_WORLD / zoom / scaleFactor
  const stopR = GRADIENT_STOP_DOT_RADIUS_WORLD / zoom / scaleFactor

  if (gradient.type === 'angular' && angularPoints != null) {
    const {
      localCenter,
      radiusX,
      radiusY,
      angle0Deg,
      localAngle0OnEllipse,
      localAngle90OnEllipse,
    } = angularPoints
    return (
      <g aria-hidden style={{ pointerEvents: 'none' }}>
        {/* Center point */}
        <circle
          cx={localCenter.x}
          cy={localCenter.y}
          r={endpointR}
          fill={HANDLE_FILL}
          vectorEffect="non-scaling-stroke"
        />
        {/* Angle 0 and 90 reference lines */}
        <line
          x1={localCenter.x}
          y1={localCenter.y}
          x2={localAngle0OnEllipse.x}
          y2={localAngle0OnEllipse.y}
          stroke={HANDLE_FILL}
          strokeWidth={lineStroke}
          vectorEffect="non-scaling-stroke"
        />
        <line
          x1={localCenter.x}
          y1={localCenter.y}
          x2={localAngle90OnEllipse.x}
          y2={localAngle90OnEllipse.y}
          stroke={HANDLE_FILL}
          strokeWidth={lineStroke}
          vectorEffect="non-scaling-stroke"
        />
        {/* Angle 0 and 90 dots on ellipse */}
        <circle
          cx={localAngle0OnEllipse.x}
          cy={localAngle0OnEllipse.y}
          r={endpointR}
          fill={HANDLE_FILL}
          vectorEffect="non-scaling-stroke"
        />
        <circle
          cx={localAngle90OnEllipse.x}
          cy={localAngle90OnEllipse.y}
          r={endpointR}
          fill={HANDLE_FILL}
          vectorEffect="non-scaling-stroke"
        />
        {/* Sweep ellipse */}
        <g
          transform={`rotate(${angle0Deg} ${localCenter.x} ${localCenter.y})`}
        >
          <ellipse
            cx={localCenter.x}
            cy={localCenter.y}
            rx={radiusX}
            ry={radiusY}
            fill="none"
            stroke={HANDLE_FILL}
            strokeWidth={lineStroke}
            vectorEffect="non-scaling-stroke"
          />
        </g>
        {/* Stop positions: swatch then white dot */}
        {angularPoints.stops.map((stop, i) => (
          <g key={i}>
            <rect
              x={stop.local.x + stopR}
              y={stop.local.y - stopR * 2}
              width={stopR * 4}
              height={stopR * 4}
              fill={stop.color}
              fillOpacity={stop.opacity ?? 1}
              stroke={HANDLE_FILL}
              strokeWidth={lineStroke / 2}
              vectorEffect="non-scaling-stroke"
            />
            <circle
              cx={stop.local.x}
              cy={stop.local.y}
              r={stopR}
              fill={HANDLE_FILL}
              vectorEffect="non-scaling-stroke"
            />
          </g>
        ))}
      </g>
    )
  }

  if (gradient.type === 'radial' && radialPoints != null) {
    const { localCenter, localTo, localWidth, stops } = radialPoints
    return (
      <g aria-hidden style={{ pointerEvents: 'none' }}>
        {/* Main radius line */}
        <line
          x1={localCenter.x}
          y1={localCenter.y}
          x2={localTo.x}
          y2={localTo.y}
          stroke={HANDLE_FILL}
          strokeWidth={lineStroke}
          vectorEffect="non-scaling-stroke"
        />
        {/* Width (ellipse) line */}
        <line
          x1={localCenter.x}
          y1={localCenter.y}
          x2={localWidth.x}
          y2={localWidth.y}
          stroke={HANDLE_FILL}
          strokeWidth={lineStroke}
          vectorEffect="non-scaling-stroke"
        />
        {/* Center point */}
        <circle
          cx={localCenter.x}
          cy={localCenter.y}
          r={endpointR}
          fill={HANDLE_FILL}
          vectorEffect="non-scaling-stroke"
        />
        {/* Radius end point */}
        <circle
          cx={localTo.x}
          cy={localTo.y}
          r={endpointR}
          fill={HANDLE_FILL}
          vectorEffect="non-scaling-stroke"
        />
        {/* Width handle */}
        <circle
          cx={localWidth.x}
          cy={localWidth.y}
          r={endpointR}
          fill={HANDLE_FILL}
          vectorEffect="non-scaling-stroke"
        />
        {/* Stop positions: swatch then white dot */}
        {stops.map((stop, i) => (
          <g key={i}>
            <rect
              x={stop.local.x + stopR}
              y={stop.local.y - stopR * 2}
              width={stopR * 4}
              height={stopR * 4}
              fill={stop.color}
              fillOpacity={stop.opacity ?? 1}
              stroke={HANDLE_FILL}
              strokeWidth={lineStroke / 2}
              vectorEffect="non-scaling-stroke"
            />
            <circle
              cx={stop.local.x}
              cy={stop.local.y}
              r={stopR}
              fill={HANDLE_FILL}
              vectorEffect="non-scaling-stroke"
            />
          </g>
        ))}
      </g>
    )
  }

  return (
    <g aria-hidden style={{ pointerEvents: 'none' }}>
      {/* Gradient line */}
      <line
        x1={points.localFrom.x}
        y1={points.localFrom.y}
        x2={points.localTo.x}
        y2={points.localTo.y}
        stroke={HANDLE_FILL}
        strokeWidth={lineStroke}
        vectorEffect="non-scaling-stroke"
      />
      {/* Start endpoint */}
      <circle
        cx={points.localFrom.x}
        cy={points.localFrom.y}
        r={endpointR}
        fill={HANDLE_FILL}
        vectorEffect="non-scaling-stroke"
      />
      {/* End endpoint */}
      <circle
        cx={points.localTo.x}
        cy={points.localTo.y}
        r={endpointR}
        fill={HANDLE_FILL}
        vectorEffect="non-scaling-stroke"
      />
      {/* Stop positions: swatch then white dot on top */}
      {points.stops.map((stop, i) => (
        <g key={i}>
          <rect
            x={stop.local.x + stopR}
            y={stop.local.y - stopR * 2}
            width={stopR * 4}
            height={stopR * 4}
            fill={stop.color}
            fillOpacity={stop.opacity ?? 1}
            stroke={HANDLE_FILL}
            strokeWidth={lineStroke / 2}
            vectorEffect="non-scaling-stroke"
          />
          <circle
            cx={stop.local.x}
            cy={stop.local.y}
            r={stopR}
            fill={HANDLE_FILL}
            vectorEffect="non-scaling-stroke"
          />
        </g>
      ))}
    </g>
  )
}
