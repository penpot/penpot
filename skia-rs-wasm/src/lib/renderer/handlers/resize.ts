/**
 * Resize handler
 * Ported from frontend start-resize: scale from handler origin, preview via setModifiers, commit on pointer up.
 */

import { Observable, EMPTY, merge } from 'rxjs'
import { map, filter, takeUntil, tap, take, scan } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { dragStopper } from '../streams/drag-stopper'
import { getSelectedIdsSet } from '../store/document-selection'
import { useWorkspaceStore } from '../store/workspace-store'
import { getCurrentPage } from '../store/doc-proxy'
import { getModifierKeys } from '../store/shortcuts-store'
import { applyModifiersAndCommit } from './utils'
import type { Point } from '../types'
import type { Matrix } from 'penpot-exporter/types'
import type { ResizeHandlePosition } from '../types'
import { invertMatrix } from '../geom/matrix'

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

const IDENTITY_MATRIX: Matrix = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 }

/** Unified resize matrix: T · S(localOrigin) · T⁻¹. When T = identity degenerates to axis-aligned scale. */
function buildResizeMatrix(
  T: Matrix,
  Tinv: Matrix,
  sx: number,
  sy: number,
  shapeCx: number,
  shapeCy: number,
  localOx: number,
  localOy: number
): Matrix {
  const Aa = sx * T.a * Tinv.a + sy * T.c * Tinv.b
  const Ab = sx * T.b * Tinv.a + sy * T.d * Tinv.b
  const Ac = sx * T.a * Tinv.c + sy * T.c * Tinv.d
  const Ad = sx * T.b * Tinv.c + sy * T.d * Tinv.d
  const woX = shapeCx + T.a * localOx + T.c * localOy
  const woY = shapeCy + T.b * localOx + T.d * localOy
  return {
    a: Aa,
    b: Ab,
    c: Ac,
    d: Ad,
    e: (1 - Aa) * woX - Ac * woY,
    f: (1 - Ad) * woY - Ab * woX,
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
  const { renderer, viewport, wasmSelectionRect } = state
  const selectedIds = getSelectedIdsSet()
  if (!renderer || !viewport || selectedIds.size < 1 || !wasmSelectionRect) return EMPTY

  const x = wasmSelectionRect.center.x - wasmSelectionRect.width / 2
  const y = wasmSelectionRect.center.y - wasmSelectionRect.height / 2
  const width = wasmSelectionRect.width <= 0 ? MIN_SIZE : wasmSelectionRect.width
  const height = wasmSelectionRect.height <= 0 ? MIN_SIZE : wasmSelectionRect.height

  const stopper = dragStopper()
  const zoom = viewport.zoom
  const mult = getHandlerMultiplier(handle)

  const selectedId = selectedIds.size === 1 ? Array.from(selectedIds)[0] : null
  const singleNode = selectedId ? getCurrentPage()?.objects[selectedId] ?? null : null
  const nodeSr = singleNode ? singleNode.selrect : null

  const T = singleNode?.transform ?? IDENTITY_MATRIX
  // Use stored inverse when available (e.g. from Figma plugin); fallback for app/legacy documents.
  const Tinv =
    singleNode?.transformInverse ??
    invertMatrix(T) ??
    IDENTITY_MATRIX

  const localW = nodeSr?.width ?? width
  const localH = nodeSr?.height ?? height
  const shapeCx = (nodeSr ? (nodeSr.x ?? 0) : x) + localW / 2
  const shapeCy = (nodeSr ? (nodeSr.y ?? 0) : y) + localH / 2

  const localOx = mult.x !== 0 ? -mult.x * (localW / 2) : 0
  const localOy = mult.y !== 0 ? -mult.y * (localH / 2) : 0

  const latestMatrixRef = { current: IDENTITY_MATRIX }
  const rafScheduledRef = { current: false }
  const modifiersAppliedRef = { current: false }
  const commitDoneRef = { current: false }

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
      const dLocalX = Tinv.a * deltaWorld.x + Tinv.c * deltaWorld.y
      const dLocalY = Tinv.b * deltaWorld.x + Tinv.d * deltaWorld.y
      let sx = noZero((localW + dLocalX * mult.x) / localW, 0.001)
      let sy = noZero((localH + dLocalY * mult.y) / localH, 0.001)

      const keys = getModifierKeys()
      const lock = keys.shift
      if (lock) {
        const s = Math.max(Math.abs(sx), Math.abs(sy))
        sx = sx < 0 ? -s : s
        sy = sy < 0 ? -s : s
      }
      const minScale = MIN_SIZE / Math.min(localW, localH)
      if (Math.abs(sx) < minScale) sx = sx < 0 ? -minScale : minScale
      if (Math.abs(sy) < minScale) sy = sy < 0 ? -minScale : minScale

      const M = buildResizeMatrix(T, Tinv, sx, sy, shapeCx, shapeCy, localOx, localOy)
      return { M }
    }),
    tap(({ M }) => {
      latestMatrixRef.current = M
      if (!rafScheduledRef.current) {
        rafScheduledRef.current = true
        requestAnimationFrame(() => {
          rafScheduledRef.current = false
          if (commitDoneRef.current) return
          modifiersAppliedRef.current = true
          const entries: Array<[string, Matrix]> = Array.from(selectedIds).map((id) => [
            id,
            latestMatrixRef.current,
          ])
          renderer.setMoveModifiersAndRender(entries)
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
      const entries: Array<[string, Matrix]> = Array.from(selectedIds).map((id) => [
        id,
        latestMatrixRef.current,
      ])
      applyModifiersAndCommit(entries)
        .then(() => {
          commitDoneRef.current = true
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          useWorkspaceStore.getState().refreshWasmSelectionRect()
          clearResizeState()
        })
        .catch(() => {
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          useWorkspaceStore.getState().refreshWasmSelectionRect()
          clearResizeState()
        })
    }),
    map(() => undefined)
  )

  return merge(resizeStream, commitOnRelease) as Observable<void>
}
