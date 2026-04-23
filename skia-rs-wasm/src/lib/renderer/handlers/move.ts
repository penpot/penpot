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
import { movePreviewWorldDelta, pointerPos, signalToObservable, viewport } from '../signals/pointer'
import { querySelectionRect, wasmSelectionRect as wasmSelRect } from '../signals/selection'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore } from '../store/workspace-store'
import { getModifierKeys } from '../store/shortcuts-store'
import { getSelectedIdsSet } from '../store/document-selection'
import { docProxy, getActiveOrSinglePageId, getPage } from '../store/doc-proxy'
import { applyModifiersAndCommit } from './utils'
import { DRAG_RENDER_INTERVAL_MS } from './drag-render-interval'
import {
  cloneSelectionRect,
  finiteSelectionRect,
  translateSelectionRectWorld,
} from './selection-rect-helpers'
import { translateMatrix } from '../geom/matrix'
import { commitChanges } from '../store/commit'
import {
  buildReparentChanges,
  findContainerAtPoint,
} from '../../components/LayersPanel/reparent'
import { rectToCenter } from '../../worker/geometry/rect'
import { ZERO_UUID } from '@skia-rs-wasm/common/conversions'
import type { IndexedShape } from '../../worker/types'
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
  const { renderer } = useWorkspaceStore.getState()
  const vp = viewport.value
  const selectedIds = getSelectedIdsSet()
  const pageId = getActiveOrSinglePageId()

  if (!renderer || !vp || selectedIds.size === 0 || !pageId) return EMPTY

  const page = getPage(pageId)
  if (!page) return EMPTY

  movePreviewWorldDelta.value = { x: 0, y: 0 }

  const stopper = dragStopper()
  const zoom = vp.zoom

  const modifiersAppliedRef = { current: false }
  let lastRenderRequestTs = 0

  // Pre-drag WASM selection rect captured at drag start (for overlay positioning).
  const baselineRect = finiteSelectionRect(wasmSelRect.peek())
    ? cloneSelectionRect(wasmSelRect.peek()!)
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
      movePreviewWorldDelta.value = worldDelta

      // 1. Update overlay SYNCHRONOUSLY (like Penpot frontend's set-temporary-selrect).
      //    This runs inside the pointer event microtask, BEFORE any RAF fires.
      if (baselineRect) {
        const preview = translateSelectionRectWorld(baselineRect, worldDelta.x, worldDelta.y)
        wasmSelRect.value = preview
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
      if (!modifiersAppliedRef.current) {
        movePreviewWorldDelta.value = { x: 0, y: 0 }
        return
      }
      const delta = lastEventDeltaRef.current
      const entries: Array<[string, Matrix]> = Array.from(selectedIds).map((id) => [
        id,
        translateMatrix(delta.x, delta.y),
      ])
      applyModifiersAndCommit(entries)
        .then(async () => {
          await reparentSelectedIfMovedIntoFrame(selectedIds, pageId)
        })
        .then(() => {
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          wasmSelRect.value = querySelectionRect(renderer, selectedIds)
          movePreviewWorldDelta.value = { x: 0, y: 0 }
        })
        .catch(() => {
          renderer.cleanModifiers()
          renderer.flushRenderSync()
          wasmSelRect.value = querySelectionRect(renderer, selectedIds)
          movePreviewWorldDelta.value = { x: 0, y: 0 }
        })
    }),
    map(() => undefined)
  )

  return merge(moveStream, commitOnRelease) as Observable<void>
}

/**
 * After the move commit lands, find the innermost frame each moved shape's
 * center is now inside. If different from the shape's current parent, emit a
 * `mov-objects` change grouped by new parent. Mirrors Penpot's canvas-drop
 * reparent — dragging a shape inside a frame adopts it into that frame.
 */
async function reparentSelectedIfMovedIntoFrame(
  selectedIds: Set<string>,
  pageId: string,
): Promise<void> {
  if (selectedIds.size === 0) return
  const page = docProxy.pageMap.get(pageId)
  if (!page) return
  const objects = page.objects as Record<string, IndexedShape>

  const excludeIds = Array.from(selectedIds)
  const byNewParent = new Map<string, string[]>()
  for (const id of selectedIds) {
    const shape = objects[id]
    if (!shape?.selrect) continue
    const center = rectToCenter(shape.selrect)
    if (!center) continue
    // When the shape's center ends up outside every container (e.g. past the
    // root frame's right/bottom edge), fall back to the root so the shape
    // escapes its old parent instead of being silently re-anchored.
    const hit = findContainerAtPoint(objects, center, excludeIds)
    const newParent = hit ?? (excludeIds.includes(ZERO_UUID) ? null : ZERO_UUID)
    if (!newParent) continue
    if (newParent === shape.parentId) continue
    const list = byNewParent.get(newParent) ?? []
    list.push(id)
    byNewParent.set(newParent, list)
  }

  for (const [parentId, shapeIds] of byNewParent) {
    const parent = objects[parentId]
    const nextIndex = parent?.shapes?.length ?? 0
    const { redoChanges, undoChanges } = buildReparentChanges({
      pageId,
      parentId,
      index: nextIndex,
      shapeIds,
      objects,
    })
    await commitChanges({ redoChanges, undoChanges, pageId })
  }
}
