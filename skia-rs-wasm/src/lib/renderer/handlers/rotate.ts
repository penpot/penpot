/**
 * Rotation handler
 * When the user drags from the rotation hit area, the selected shape rotates so its angle follows
 * the cursor (angle from selection center to cursor). Preview via setMoveModifiers(rotation matrix);
 * commit node.rotation on pointer up.
 */

import { Observable, EMPTY, merge } from 'rxjs'
import { map, filter, takeUntil, tap, take } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore } from '../store/workspace-store'
import { screenToWorld } from '../viewport'
import { applyModifiersAndCommit } from './utils'
import type { Point } from '../types'
import type { Matrix } from 'penpot-exporter/types'

function angleDegFromCenter(cx: number, cy: number, wx: number, wy: number): number {
  return Math.atan2(wy - cy, wx - cx) * (180 / Math.PI)
}

/** Build 2D affine rotation matrix around center (cx, cy). Angle in degrees. Used for drag modifier. */
function rotationMatrix(cx: number, cy: number, angleDeg: number): Matrix {
  const theta = (angleDeg * Math.PI) / 180
  const cos = Math.cos(theta)
  const sin = Math.sin(theta)
  return {
    a: cos,
    b: sin,
    c: -sin,
    d: cos,
    e: cx * (1 - cos) + cy * sin,
    f: cy * (1 - cos) - cx * sin,
  }
}

export function startRotateSelected(initialPosition: Point): Observable<void> {
  const state = useWorkspaceStore.getState()
  const { renderer, viewport, selectedIds, selectedNodes, wasmSelectionRect } = state

  if (!renderer || !viewport || selectedIds.size < 1) {
    return EMPTY
  }

  const ids = Array.from(selectedIds)
  const isSingle = ids.length === 1
  const singleNode = selectedNodes?.[0]

  // Single selection: require valid node with selrect (current behavior)
  if (isSingle) {
    if (!singleNode || singleNode.id !== ids[0] || !singleNode.selrect) {
      return EMPTY
    }
  }

  // Rotation center: selection rect center (works for single and group)
  let cx: number
  let cy: number
  const startRotations = new Map<string, number>()

  if (isSingle && singleNode?.selrect) {
    const sr = singleNode.selrect
    const x = (sr as { x?: number }).x ?? 0
    const y = (sr as { y?: number }).y ?? 0
    const w = (sr as { width?: number }).width ?? 0
    const h = (sr as { height?: number }).height ?? 0
    cx = x + w / 2
    cy = y + h / 2
    startRotations.set(ids[0], (singleNode as { rotation?: number }).rotation ?? 0)
  } else {
    if (!wasmSelectionRect) return EMPTY
    cx = wasmSelectionRect.center.x
    cy = wasmSelectionRect.center.y
    selectedNodes?.forEach((n) => {
      if (n && ids.includes(n.id))
        startRotations.set(n.id, (n as { rotation?: number }).rotation ?? 0)
    })
  }

  const initialWorld = screenToWorld(viewport, initialPosition.x, initialPosition.y)
  const initialAngleDeg = angleDegFromCenter(cx, cy, initialWorld.x, initialWorld.y)

  const stopper = dragStopper()
  const latestDeltaDegRef = { current: 0 }
  const rafScheduledRef = { current: false }
  const modifiersAppliedRef = { current: false }

  const rotateStream = mousePosition$.pipe(
    filter((pos): pos is NonNullable<typeof pos> => pos !== null),
    map((pos) => screenToWorld(viewport, pos.x, pos.y)),
    map((world) => angleDegFromCenter(cx, cy, world.x, world.y)),
    map((currentAngleDeg) => currentAngleDeg - initialAngleDeg),
    tap((deltaDeg) => {
      latestDeltaDegRef.current = deltaDeg
      if (!rafScheduledRef.current) {
        rafScheduledRef.current = true
        requestAnimationFrame(() => {
          rafScheduledRef.current = false
          modifiersAppliedRef.current = true
          const delta = latestDeltaDegRef.current
          const matrix = rotationMatrix(cx, cy, delta)
          const entries: Array<[string, Matrix]> = ids.map((id) => [id, matrix])
          renderer.setMoveModifiersAndRender(entries)
          useWorkspaceStore.getState().refreshWasmSelectionRect()
        })
      }
    }),
    map(() => undefined),
    takeUntil(stopper)
  )

  const commitOnRelease = stopper.pipe(
    take(1),
    tap(() => {
      if (!modifiersAppliedRef.current) {
        const store = useWorkspaceStore.getState()
        store.setRotationCorner(null)
        store.setIsRotating(false)
        return
      }
      const deltaDeg = latestDeltaDegRef.current
      const matrix = rotationMatrix(cx, cy, deltaDeg)
      const entries: Array<[string, Matrix]> = ids.map((id) => [id, matrix])
      applyModifiersAndCommit(entries)
        .then(() => {
          const storeAfter = useWorkspaceStore.getState()
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          storeAfter.refreshWasmSelectionRect()
          requestAnimationFrame(() => renderer.requestRenderFrame())
          storeAfter.setRotationCorner(null)
          storeAfter.setIsRotating(false)
        })
        .catch(() => {
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          const storeCatch = useWorkspaceStore.getState()
          storeCatch.refreshWasmSelectionRect()
          requestAnimationFrame(() => renderer.requestRenderFrame())
          storeCatch.setRotationCorner(null)
          storeCatch.setIsRotating(false)
        })
    }),
    map(() => undefined)
  )

  return merge(rotateStream, commitOnRelease) as Observable<void>
}
