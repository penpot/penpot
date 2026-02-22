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
import { makeSelrect } from '@skia-rs-wasm/common'
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
  const rotationDeg = (node as { rotation?: number }).rotation
  const cx = x + width / 2
  const cy = y + height / 2
  const hasRotation = rotationDeg !== undefined && rotationDeg !== 0

  const theta_r = hasRotation ? (rotationDeg! * Math.PI) / 180 : 0
  const cos_r = Math.cos(theta_r)
  const sin_r = Math.sin(theta_r)
  const wo_x = hasRotation ? cx + (origin.x - cx) * cos_r - (origin.y - cy) * sin_r : origin.x
  const wo_y = hasRotation ? cy + (origin.x - cx) * sin_r + (origin.y - cy) * cos_r : origin.y

  const identityLocal = resizeMatrix(origin.x, origin.y, 1, 1)
  const latestMatrixRef = { current: identityLocal }
  const latestLocalMatrixRef = { current: identityLocal }
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
      const localDeltaX = deltaWorld.x * cos_r + deltaWorld.y * sin_r
      const localDeltaY = -deltaWorld.x * sin_r + deltaWorld.y * cos_r
      const deltav = { x: localDeltaX * mult.x, y: localDeltaY * mult.y }
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
      const M_local = resizeMatrix(origin.x, origin.y, sx, sy)
      let M_world: Matrix
      if (hasRotation) {
        const rsr_a = sx * cos_r * cos_r + sy * sin_r * sin_r
        const rsr_b = (sx - sy) * sin_r * cos_r
        const rsr_c = (sx - sy) * sin_r * cos_r
        const rsr_d = sx * sin_r * sin_r + sy * cos_r * cos_r
        M_world = {
          a: rsr_a,
          b: rsr_b,
          c: rsr_c,
          d: rsr_d,
          e: wo_x * (1 - rsr_a) - wo_y * rsr_c,
          f: wo_y * (1 - rsr_d) - wo_x * rsr_b,
        }
      } else {
        M_world = M_local
      }
      return { M_local, M_world }
    }),
    tap(({ M_local, M_world }) => {
      latestLocalMatrixRef.current = M_local
      latestMatrixRef.current = M_world
      if (!rafScheduledRef.current) {
        rafScheduledRef.current = true
        requestAnimationFrame(() => {
          rafScheduledRef.current = false
          modifiersAppliedRef.current = true
          renderer.setMoveModifiersAndRender([[selectedId, latestMatrixRef.current]])
          useWorkspaceStore.getState().refreshWasmSelectionRect()
        })
      }
    }),
    map(() => undefined),
    takeUntil(stopper)
  )

  function clearResizeState(): void {
    const store = useWorkspaceStore.getState()
    store.setIsResizing(false)
    store.setResizeHandle(null)
  }

  const commitOnRelease = stopper.pipe(
    take(1),
    tap(() => {
      if (!modifiersAppliedRef.current) {
        clearResizeState()
        return
      }

      let updates: Partial<typeof node> | null
      if (hasRotation) {
        const ml = latestLocalMatrixRef.current
        const mw = latestMatrixRef.current
        const tf = (px: number, py: number) => ({
          x: ml.a * px + ml.c * py + ml.e,
          y: ml.b * px + ml.d * py + ml.f,
        })
        const localCorners = [tf(x, y), tf(x + width, y), tf(x + width, y + height), tf(x, y + height)]
        const nlw = Math.max(...localCorners.map((p) => p.x)) - Math.min(...localCorners.map((p) => p.x))
        const nlh = Math.max(...localCorners.map((p) => p.y)) - Math.min(...localCorners.map((p) => p.y))
        const corrCx = mw.a * cx + mw.c * cy + mw.e
        const corrCy = mw.b * cx + mw.d * cy + mw.f
        const corrX = corrCx - nlw / 2
        const corrY = corrCy - nlh / 2
        const selrect = makeSelrect(corrX, corrY, nlw, nlh)
        const unrotCorners = [
          { x: corrX, y: corrY },
          { x: corrX + nlw, y: corrY },
          { x: corrX + nlw, y: corrY + nlh },
          { x: corrX, y: corrY + nlh },
        ]
        const points = unrotCorners.map((p) => ({
          x: cos_r * (p.x - corrCx) - sin_r * (p.y - corrCy) + corrCx,
          y: sin_r * (p.x - corrCx) + cos_r * (p.y - corrCy) + corrCy,
        }))
        updates = { selrect, points } as Partial<typeof node>
        if (typeof node.x === 'number') (updates as Record<string, unknown>).x = corrX
        if (typeof node.y === 'number') (updates as Record<string, unknown>).y = corrY
        if (typeof (node as { width?: number }).width === 'number') (updates as Record<string, unknown>).width = nlw
        if (typeof (node as { height?: number }).height === 'number') (updates as Record<string, unknown>).height = nlh
      } else {
        updates = applyResizeTransformToNode(node, latestLocalMatrixRef.current)
      }

      if (!updates) {
        clearResizeState()
        return
      }
      updateNode(selectedId, updates)
        .then(() => {
          renderer.cleanModifiers()
          useWorkspaceStore.getState().refreshWasmSelectionRect()
          clearResizeState()
        })
        .catch(() => {
          renderer.cleanModifiers()
          useWorkspaceStore.getState().refreshWasmSelectionRect()
          clearResizeState()
        })
    }),
    map(() => undefined)
  )

  return merge(resizeStream, commitOnRelease) as Observable<void>
}
