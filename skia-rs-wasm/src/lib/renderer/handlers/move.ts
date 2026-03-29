/**
 * Move handler
 * Ported from frontend/src/app/main/data/workspace/transforms.cljs start-move-selected
 * Uses WASM modifiers for preview during drag; commits on pointer up. Shift constrains to one axis.
 *
 * Architecture (matching Penpot frontend):
 * - Overlay (selection rect SVG) is updated SYNCHRONOUSLY from each pointer event
 * - WASM canvas render is scheduled ASYNC via requestRender (RAF-coalesced)
 * - This ensures the overlay is always responsive even when _render blocks (~55ms)
 */

import { Observable, EMPTY, merge } from 'rxjs'
import { map, filter, takeUntil, tap, take, scan } from 'rxjs/operators'
import { pointerPos, signalToObservable } from '../signals/pointer'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore } from '../store/workspace-store'
import { getModifierKeys } from '../store/shortcuts-store'
import { getSelectedIdsSet } from '../store/document-selection'
import { getActiveOrSinglePageId, getPage } from '../store/doc-proxy'
import { applyModifiersAndCommit } from './utils'
import { DRAG_RENDER_INTERVAL_MS } from './drag-render-interval'
import {
  cloneSelectionRect,
  finiteSelectionRect,
  translateSelectionRectWorld,
} from './selection-rect-helpers'
import { translateMatrix } from '../geom/matrix'
import type { Point } from '../types'
import type { Matrix } from 'penpot-exporter/types'

function constrainDeltaByShift(delta: { x: number; y: number }): { x: number; y: number } {
  const keys = getModifierKeys()
  if (!keys.shift) return delta
  const xDisp = Math.abs(delta.x) > Math.abs(delta.y)
  if (xDisp) return { x: delta.x, y: 0 }
  return { x: 0, y: delta.y }
}

export function startMoveSelected(initialPosition: Point): Observable<void> {
  const state = useWorkspaceStore.getState()
  const { renderer, viewport } = state
  const selectedIds = getSelectedIdsSet()
  const pageId = getActiveOrSinglePageId()

  if (!renderer || !viewport || selectedIds.size === 0 || !pageId) return EMPTY

  const page = getPage(pageId)
  if (!page) return EMPTY

  useWorkspaceStore.getState().setMovePreviewWorldDelta({ x: 0, y: 0 })

  const stopper = dragStopper()
  const zoom = viewport.zoom

  const modifiersAppliedRef = { current: false }
  let lastRenderRequestTs = 0

  // Pre-drag WASM selection rect captured at drag start (for overlay positioning).
  const baselineRect = finiteSelectionRect(state.wasmSelectionRect)
    ? cloneSelectionRect(state.wasmSelectionRect)
    : null

  const lastEventDeltaRef = { current: { x: 0, y: 0 } }

  const DRAG_THRESHOLD_SCREEN_PX = 5
  const moveStream = signalToObservable(pointerPos).pipe(
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
      modifiersAppliedRef.current = true
      lastEventDeltaRef.current = { x: worldDelta.x, y: worldDelta.y }
      useWorkspaceStore.getState().setMovePreviewWorldDelta(worldDelta)

      // 1. Update overlay SYNCHRONOUSLY (like Penpot frontend's set-temporary-selrect).
      //    This runs inside the pointer event microtask, BEFORE any RAF fires.
      if (baselineRect) {
        const preview = translateSelectionRectWorld(baselineRect, worldDelta.x, worldDelta.y)
        useWorkspaceStore.getState().setWasmSelectionRect(preview)
      }

      // 2. Clean + propagate + set WASM modifiers (every event, ~0.1ms).
      //    Matches the frontend's set-wasm-modifiers pattern.
      renderer.cleanModifiers()
      const entries: Array<[string, Matrix]> = Array.from(selectedIds, (id) => [
        id,
        translateMatrix(worldDelta.x, worldDelta.y),
      ])
      renderer.setMoveModifiersNoRender(entries)

      // 3. Throttle canvas render (~60 Hz); overlay still updates every pointer event.
      const now = performance.now()
      if (now - lastRenderRequestTs >= DRAG_RENDER_INTERVAL_MS) {
        lastRenderRequestTs = now
        renderer.requestRenderFrame()
      }

    }),
    map(() => undefined),
    takeUntil(stopper)
  )

  const commitOnRelease = stopper.pipe(
    take(1),
    tap(() => {
      const store = useWorkspaceStore.getState()
      if (!modifiersAppliedRef.current) {
        store.setMovePreviewWorldDelta({ x: 0, y: 0 })
        return
      }
      const delta = lastEventDeltaRef.current
      const entries: Array<[string, Matrix]> = Array.from(selectedIds).map((id) => [
        id,
        translateMatrix(delta.x, delta.y),
      ])
      applyModifiersAndCommit(entries)
        .then(() => {
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          useWorkspaceStore.getState().refreshWasmSelectionRect()
          useWorkspaceStore.getState().setMovePreviewWorldDelta({ x: 0, y: 0 })
        })
        .catch(() => {
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          useWorkspaceStore.getState().refreshWasmSelectionRect()
          useWorkspaceStore.getState().setMovePreviewWorldDelta({ x: 0, y: 0 })
        })
    }),
    map(() => undefined)
  )

  return merge(moveStream, commitOnRelease) as Observable<void>
}
