/**
 * Move handler
 * Ported from frontend/src/app/main/data/workspace/transforms.cljs start-move-selected
 * Uses WASM modifiers for preview during drag; commits on pointer up. Shift constrains to one axis.
 */

import { Observable, EMPTY, merge } from 'rxjs'
import { map, filter, takeUntil, tap, take, scan } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore } from '../store/workspace-store'
import { getModifierKeys } from '../store/shortcuts-store'
import { updatePage, applyMoveDeltaToPage } from '../store/page-crud'
import type { Point } from '../viewport'
import type { Matrix } from '@penpot-exporter/types'

function translateMatrix(dx: number, dy: number): Matrix {
  return { a: 1, b: 0, c: 0, d: 1, e: dx, f: dy }
}

function constrainDeltaByShift(delta: { x: number; y: number }): { x: number; y: number } {
  const keys = getModifierKeys()
  if (!keys.shift) return delta
  const xDisp = Math.abs(delta.x) > Math.abs(delta.y)
  if (xDisp) return { x: delta.x, y: 0 }
  return { x: 0, y: delta.y }
}

export function startMoveSelected(initialPosition: Point): Observable<void> {
  const state = useWorkspaceStore.getState()
  const { renderer, viewport, selectedIds, pageId, documentModel } = state

  if (!renderer || !viewport || selectedIds.size === 0 || !pageId) return EMPTY

  const page = documentModel?.getPage(pageId)
  if (!page) return EMPTY

  const stopper = dragStopper()
  const zoom = viewport.zoom

  const latestWorldDeltaRef = { current: { x: 0, y: 0 } }
  const rafScheduledRef = { current: false }
  const modifiersAppliedRef = { current: false }

  /** Activation threshold (min 1px): drag starts after pointer moves past this; once activated, all deltas apply including back inside this zone. */
  const DRAG_THRESHOLD_SCREEN_PX = 5
  const moveStream = mousePosition$.pipe(
    filter((pos): pos is NonNullable<typeof pos> => pos !== null),
    map((pos) => ({
      x: pos.x - initialPosition.x,
      y: pos.y - initialPosition.y,
    })),
    scan(
      (acc: { delta: { x: number; y: number }; activated: boolean }, delta) => {
        const mag = Math.sqrt(delta.x ** 2 + delta.y ** 2)
        return { delta, activated: acc.activated || mag > DRAG_THRESHOLD_SCREEN_PX }
      },
      { delta: { x: 0, y: 0 }, activated: false } as { delta: { x: number; y: number }; activated: boolean }
    ),
    filter(({ activated }) => activated),
    map(({ delta }) => delta),
    map((delta) => ({
      x: delta.x / zoom,
      y: delta.y / zoom,
    })),
    map(constrainDeltaByShift),
    tap((worldDelta) => {
      latestWorldDeltaRef.current = worldDelta
      if (!rafScheduledRef.current) {
        rafScheduledRef.current = true
        requestAnimationFrame(() => {
          rafScheduledRef.current = false
          const delta = latestWorldDeltaRef.current
          modifiersAppliedRef.current = true
          const entries: Array<[string, Matrix]> = []
          selectedIds.forEach((id) => {
            entries.push([id, translateMatrix(delta.x, delta.y)])
          })
          renderer.setMoveModifiers(entries)
          useWorkspaceStore.getState().setMovePreviewDelta(delta)
        })
      }
    }),
    map(() => undefined),
    takeUntil(stopper)
  )

  const commitOnRelease = stopper.pipe(
    take(1),
    tap(() => {
      const store = useWorkspaceStore.getState()
      store.setMovePreviewDelta(null)
      if (!modifiersAppliedRef.current) {
        store.setIsMoving(false)
        return
      }
      const delta = latestWorldDeltaRef.current
      const updatedPage = applyMoveDeltaToPage(page, selectedIds, delta)
      updatePage({ ...updatedPage, pageId })
        .then(() => {
          renderer.cleanModifiers()
          useWorkspaceStore.getState().setIsMoving(false)
        })
        .catch(() => {
          renderer.cleanModifiers()
          useWorkspaceStore.getState().setIsMoving(false)
        })
    }),
    map(() => undefined)
  )

  return merge(moveStream, commitOnRelease) as Observable<void>
}
