/**
 * Drag-to-create shapes on the canvas (e.g. rectangle), similar to Penpot workspace draw tools.
 */

import { Observable, EMPTY, concat, of } from 'rxjs'
import { filter, map, scan, switchMap, take, takeUntil, tap } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore } from '../store/workspace-store'
import { getActiveOrSinglePageId, getPage } from '../store/doc-proxy'
import { screenToWorld } from '../viewport'
import { makeSelrect } from '../../worker/types'
import { applyChanges } from '../../page-crud'
import { createRect } from '../../../dev/node-factory'
import type { AddObjChange } from 'penpot-exporter/types'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

/** Minimum rubber-band size in screen pixels before committing a rect. */
const MIN_DRAW_SCREEN_PX = 3

export function handleDrawRect(): Observable<void> {
  const { viewport, pageId } = useWorkspaceStore.getState()
  const effectivePageId = pageId ?? getActiveOrSinglePageId()
  const page = effectivePageId ? getPage(effectivePageId) : undefined

  if (!viewport || !effectivePageId || !page) {
    useWorkspaceStore.getState().setIsDrawingShape(false)
    useWorkspaceStore.getState().setShapeDrawPreview(null)
    return EMPTY
  }

  const stopper = dragStopper()

  return mousePosition$.pipe(
    filter((pos): pos is { x: number; y: number } => pos !== null),
    take(1),
    switchMap((initialPosition) => {
      const selrectStream = mousePosition$.pipe(
        filter((pos): pos is { x: number; y: number } => pos !== null),
        scan(
          (_acc, pos) => {
            const x1 = Math.min(initialPosition.x, pos.x)
            const y1 = Math.min(initialPosition.y, pos.y)
            const x2 = Math.max(initialPosition.x, pos.x)
            const y2 = Math.max(initialPosition.y, pos.y)
            return makeSelrect(x1, y1, x2 - x1, y2 - y1)
          },
          makeSelrect(0, 0, 0, 0)
        ),
        takeUntil(stopper)
      )

      let lastRect = makeSelrect(0, 0, 0, 0)

      return concat(
        selrectStream.pipe(
          tap((rect) => {
            lastRect = rect
            useWorkspaceStore.getState().setShapeDrawPreview(rect)
          }),
          map(() => undefined)
        ),
        of(null).pipe(
          tap(() => {
            useWorkspaceStore.getState().setShapeDrawPreview(null)
            useWorkspaceStore.getState().setIsDrawingShape(false)

            if (lastRect.width < MIN_DRAW_SCREEN_PX || lastRect.height < MIN_DRAW_SCREEN_PX) {
              return
            }

            const vp = useWorkspaceStore.getState().viewport ?? viewport
            if (!vp) return

            const worldOrigin = screenToWorld(vp, lastRect.x, lastRect.y)
            const w = lastRect.width / vp.zoom
            const h = lastRect.height / vp.zoom
            if (w < 1e-6 || h < 1e-6) return

            const currentPage = effectivePageId ? getPage(effectivePageId) : undefined
            if (!currentPage) return

            const root = Object.values(currentPage.objects).find((o) => o.parentId == null)
            const rootId = root?.id ?? ROOT_UUID
            const newNode = createRect({
              x: worldOrigin.x,
              y: worldOrigin.y,
              width: w,
              height: h,
              parentId: rootId,
              fillColor: '#3B82F6',
              fillOpacity: 0.85,
              strokeColor: '#1E40AF',
              strokeWidth: 2,
            })

            const addChange: AddObjChange = {
              type: 'add-obj',
              id: newNode.id,
              obj: newNode,
              frameId: rootId,
              parentId: rootId,
              index: root?.shapes?.length ?? 0,
              pageId: effectivePageId,
            }
            void applyChanges([addChange])
            useWorkspaceStore.getState().setSelectedIds(new Set([newNode.id]))
          }),
          map(() => undefined)
        )
      )
    })
  )
}
