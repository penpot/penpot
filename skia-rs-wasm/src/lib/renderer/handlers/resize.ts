/**
 * Resize handler
 * Ported from frontend start-resize: scale from handler origin, preview via setModifiers, commit on pointer up.
 */

import { Observable, EMPTY, merge } from 'rxjs'
import { map, filter, takeUntil, tap, take, scan } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore } from '../store/workspace-store'
import { getModifierKeys } from '../store/shortcuts-store'
import { updateNode, applyResizeTransformToNode } from '../store/page-crud'
import type { Point } from '@skia-rs-wasm/common'
import type { Matrix } from '@penpot-exporter/types'
import type { ResizeHandlePosition } from '../types'

const MIN_SIZE = 1

function getHandlerMultiplier(handle: ResizeHandlePosition): { x: number; y: number } {
  switch (handle) {
    case 'right':
      return { x: 1, y: 0 }
    case 'bottom':
      return { x: 0, y: 1 }
    case 'left':
      return { x: -1, y: 0 }
    case 'top':
      return { x: 0, y: -1 }
    case 'top-right':
      return { x: 1, y: -1 }
    case 'top-left':
      return { x: -1, y: -1 }
    case 'bottom-right':
      return { x: 1, y: 1 }
    case 'bottom-left':
      return { x: -1, y: 1 }
    default:
      return { x: 1, y: 1 }
  }
}

function getHandlerResizeOrigin(
  x: number,
  y: number,
  width: number,
  height: number,
  handle: ResizeHandlePosition
): { x: number; y: number } {
  const mx = x + width / 2
  const my = y + height / 2
  const ex = x + width
  const ey = y + height
  switch (handle) {
    case 'right':
      return { x, y: my }
    case 'bottom':
      return { x: mx, y }
    case 'left':
      return { x: ex, y: my }
    case 'top':
      return { x: mx, y: ey }
    case 'top-right':
      return { x, y: ey }
    case 'top-left':
      return { x: ex, y: ey }
    case 'bottom-right':
      return { x, y }
    case 'bottom-left':
      return { x: ex, y }
    default:
      return { x, y }
  }
}

/** Build resize matrix: T(origin) * S(sx,sy) * T(-origin) */
function resizeMatrix(
  ox: number,
  oy: number,
  sx: number,
  sy: number
): Matrix {
  return {
    a: sx,
    b: 0,
    c: 0,
    d: sy,
    e: ox * (1 - sx),
    f: oy * (1 - sy),
  }
}

function noZero(v: number, min: number): number {
  if (v >= 0 && v < min) return min
  if (v < 0 && v > -min) return -min
  return v
}

export function startResizeSelected(
  initialPosition: Point,
  handle: ResizeHandlePosition
): Observable<void> {
  const state = useWorkspaceStore.getState()
  const { renderer, viewport, selectedIds } = state
  const selectedId = selectedIds.size ? Array.from(selectedIds)[0] : null
  
  const node = state.selectedNodes?.[0]
  if (!renderer || !viewport || selectedIds.size !== 1 || !selectedId || !node || node.id !== selectedId) return EMPTY

  const { x, y, width: w, height: h } = node.selrect
  const width = w <= 0 ? MIN_SIZE : w
  const height = h <= 0 ? MIN_SIZE : h

  const stopper = dragStopper()
  const zoom = viewport.zoom
  const mult = getHandlerMultiplier(handle)
  const origin = getHandlerResizeOrigin(x, y, width, height, handle)

  const latestMatrixRef = { current: resizeMatrix(origin.x, origin.y, 1, 1) }
  const rafScheduledRef = { current: false }
  const modifiersAppliedRef = { current: false }

  const RESIZE_THRESHOLD_SCREEN_PX = 5
  const resizeStream = mousePosition$.pipe(
    filter((pos): pos is NonNullable<typeof pos> => pos !== null),
    map((pos) => {
      const deltaScreen = { x: pos.x - initialPosition.x, y: pos.y - initialPosition.y }
      const mag = Math.sqrt(deltaScreen.x ** 2 + deltaScreen.y ** 2)
      return { pos, activated: mag > RESIZE_THRESHOLD_SCREEN_PX }
    }),
    scan(
      (acc: { activated: boolean }, { pos, activated }) => ({
        activated: acc.activated || activated,
        pos,
      }),
      { activated: false, pos: null as Point | null }
    ),
    filter((acc): acc is { activated: boolean; pos: Point } => acc.activated && acc.pos !== null),
    map((acc) => ({
      x: (acc.pos.x - initialPosition.x) / zoom,
      y: (acc.pos.y - initialPosition.y) / zoom,
    })),
    map((deltaWorld) => {
      const deltav = { x: deltaWorld.x * mult.x, y: deltaWorld.y * mult.y }
      let sx = (width + deltav.x) / width
      let sy = (height + deltav.y) / height
      sx = noZero(sx, 0.001)
      sy = noZero(sy, 0.001)
      const lock = getModifierKeys().shift
      if (lock) {
        const s = Math.max(Math.abs(sx), Math.abs(sy))
        sx = sx < 0 ? -s : s
        sy = sy < 0 ? -s : s
      }
      const minScale = MIN_SIZE / Math.min(width, height)
      if (Math.abs(sx) < minScale) sx = sx < 0 ? -minScale : minScale
      if (Math.abs(sy) < minScale) sy = sy < 0 ? -minScale : minScale
      return resizeMatrix(origin.x, origin.y, sx, sy)
    }),
    tap((matrix) => {
      latestMatrixRef.current = matrix
      if (!rafScheduledRef.current) {
        rafScheduledRef.current = true
        requestAnimationFrame(() => {
          rafScheduledRef.current = false
          modifiersAppliedRef.current = true
          renderer.setMoveModifiers([[selectedId, latestMatrixRef.current]])
          const m = latestMatrixRef.current
          const tf = (px: number, py: number) => ({
            x: m.a * px + m.c * py + m.e,
            y: m.b * px + m.d * py + m.f,
          })
          const corners = [tf(x, y), tf(x + width, y), tf(x + width, y + height), tf(x, y + height)]
          const minX = Math.min(...corners.map((p) => p.x))
          const minY = Math.min(...corners.map((p) => p.y))
          const maxX = Math.max(...corners.map((p) => p.x))
          const maxY = Math.max(...corners.map((p) => p.y))
          useWorkspaceStore.getState().setResizePreviewBounds({
            x: minX,
            y: minY,
            width: maxX - minX,
            height: maxY - minY,
          })
        })
      }
    }),
    map(() => undefined),
    takeUntil(stopper)
  )

  const commitOnRelease = stopper.pipe(
    take(1),
    tap(() => {
      const updates = applyResizeTransformToNode(node, latestMatrixRef.current)
      if (!modifiersAppliedRef.current) return
      renderer.cleanModifiers()
      if (updates) {
        updateNode(selectedId, updates).catch(() => {})
      }
    }),
    map(() => undefined)
  )

  return merge(resizeStream, commitOnRelease) as Observable<void>
}
