/**
 * Rotation handler
 * When the user drags from the rotation hit area, the selected shape rotates so its angle follows
 * the cursor (angle from selection center to cursor). Preview via setMoveModifiers(rotation matrix);
 * commit node.rotation on pointer up.
 *
 * Overlay + property panel stay in sync with the pointer (same pattern as move.ts): synchronous
 * `setWasmSelectionRect` from a baseline rect each event; WASM uses `setMoveModifiersNoRender` +
 * throttled `requestRenderFrame` so `_render` does not block overlay updates.
 */

import { Observable, EMPTY, merge } from 'rxjs'
import { map, filter, takeUntil, tap, take } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { dragStopper } from '../streams/drag-stopper'
import { getSelectedIdsSet } from '../store/document-selection'
import { useWorkspaceStore } from '../store/workspace-store'
import { getCurrentPage } from '../store/doc-proxy'
import { screenToWorld } from '../viewport'
import { applyModifiersAndCommit } from './utils'
import { DRAG_RENDER_INTERVAL_MS } from './drag-render-interval'
import {
  cloneSelectionRect,
  finiteSelectionRect,
  rotateSelectionRectAroundPivot,
} from './selection-rect-helpers'
import { rotationMatrixAroundPoint } from '../geom/matrix'
import type { Point } from '../types'
import type { Matrix } from 'penpot-exporter/types'
import type { IndexedNode } from '../../worker/types'

function angleDegFromCenter(cx: number, cy: number, wx: number, wy: number): number {
  return Math.atan2(wy - cy, wx - cx) * (180 / Math.PI)
}

export function startRotateSelected(initialPosition: Point): Observable<void> {
  let state = useWorkspaceStore.getState()
  const { renderer, viewport, wasmSelectionRect } = state
  const selectedIds = getSelectedIdsSet()

  if (!renderer || !viewport || selectedIds.size < 1) {
    return EMPTY
  }

  if (!finiteSelectionRect(wasmSelectionRect)) {
    state.refreshWasmSelectionRect()
    state = useWorkspaceStore.getState()
  }

  const ids = Array.from(selectedIds)
  const isSingle = ids.length === 1
  const pageObjects = getCurrentPage()?.objects
  const selectedNodes = ids
    .map((id) => pageObjects?.[id])
    .filter((node): node is IndexedNode => node !== undefined)
  const singleNode = selectedNodes[0]

  if (isSingle) {
    if (!singleNode || singleNode.id !== ids[0] || !singleNode.selrect) {
      return EMPTY
    }
  }

  let cx: number
  let cy: number

  if (isSingle && singleNode?.selrect) {
    const sr = singleNode.selrect
    const x = (sr as { x?: number }).x ?? 0
    const y = (sr as { y?: number }).y ?? 0
    const w = (sr as { width?: number }).width ?? 0
    const h = (sr as { height?: number }).height ?? 0
    cx = x + w / 2
    cy = y + h / 2
  } else {
    const wr = useWorkspaceStore.getState().wasmSelectionRect
    if (!wr) return EMPTY
    cx = wr.center.x
    cy = wr.center.y
  }

  const baselineRect = finiteSelectionRect(useWorkspaceStore.getState().wasmSelectionRect)
    ? cloneSelectionRect(useWorkspaceStore.getState().wasmSelectionRect!)
    : null

  const initialWorld = screenToWorld(viewport, initialPosition.x, initialPosition.y)
  const initialAngleDeg = angleDegFromCenter(cx, cy, initialWorld.x, initialWorld.y)

  const stopper = dragStopper()
  const latestDeltaDegRef = { current: 0 }
  const modifiersAppliedRef = { current: false }
  const commitDoneRef = { current: false }
  let lastRenderRequestTs = 0

  const rotateStream = mousePosition$.pipe(
    filter((pos): pos is NonNullable<typeof pos> => pos !== null),
    map((pos) => screenToWorld(viewport, pos.x, pos.y)),
    map((world) => angleDegFromCenter(cx, cy, world.x, world.y)),
    map((currentAngleDeg) => currentAngleDeg - initialAngleDeg),
    tap((deltaDeg) => {
      if (commitDoneRef.current) return
      latestDeltaDegRef.current = deltaDeg
      modifiersAppliedRef.current = true

      const store = useWorkspaceStore.getState()
      store.setRotatePreviewDeltaDeg(deltaDeg)

      if (baselineRect) {
        const preview = rotateSelectionRectAroundPivot(baselineRect, cx, cy, deltaDeg)
        store.setWasmSelectionRect(preview)
      }

      renderer.cleanModifiers()
      const matrix = rotationMatrixAroundPoint(cx, cy, deltaDeg)
      const entries: Array<[string, Matrix]> = ids.map((id) => [id, matrix])
      renderer.setMoveModifiersNoRender(entries)

      const now = performance.now()
      if (now - lastRenderRequestTs >= DRAG_RENDER_INTERVAL_MS) {
        lastRenderRequestTs = now
        renderer.requestRenderFrame()
      }
    }),
    map(() => undefined),
    takeUntil(stopper),
  )

  const commitOnRelease = stopper.pipe(
    take(1),
    tap(() => {
      const store = useWorkspaceStore.getState()
      store.setRotatePreviewDeltaDeg(0)
      if (!modifiersAppliedRef.current) {
        return
      }
      const deltaDeg = latestDeltaDegRef.current
      const matrix = rotationMatrixAroundPoint(cx, cy, deltaDeg)
      const entries: Array<[string, Matrix]> = ids.map((id) => [id, matrix])
      applyModifiersAndCommit(entries)
        .then(() => {
          commitDoneRef.current = true
          const storeAfter = useWorkspaceStore.getState()
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          storeAfter.refreshWasmSelectionRect()
          requestAnimationFrame(() => renderer.requestRenderFrame())
        })
        .catch(() => {
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          const storeCatch = useWorkspaceStore.getState()
          storeCatch.refreshWasmSelectionRect()
          requestAnimationFrame(() => renderer.requestRenderFrame())
        })
    }),
    map(() => undefined),
  )

  return merge(rotateStream, commitOnRelease) as Observable<void>
}
