/**
 * Move handler
 * Ported from frontend/src/app/main/data/workspace/transforms.cljs start-move-selected
 */

import { Observable, EMPTY } from 'rxjs'
import { map, filter, takeUntil, tap } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore, selectCurrentPageNodes } from '../store/workspace-store'
import { updatePage } from '../store/page-crud'
import type { Point } from '../viewport'

export function startMoveSelected(initialPosition: Point): Observable<void> {
  const state = useWorkspaceStore.getState()
  const { renderer, viewport, selectedIds, pageId, pageMap } = state
  const nodes = selectCurrentPageNodes(state)

  if (!renderer || !viewport || selectedIds.size === 0 || !pageId) return EMPTY

  const page = pageMap.get(pageId)
  if (!page) return EMPTY

  const stopper = dragStopper()
  const zoom = viewport.zoom

  // Store initial node positions to calculate deltas correctly
  const initialNodePositions = new Map<string, { x: number; y: number; width: number; height: number }>()
  nodes.forEach((node) => {
    if (selectedIds.has(node.id)) {
      initialNodePositions.set(node.id, {
        x: node.x ?? 0,
        y: node.y ?? 0,
        width: node.width ?? 0,
        height: node.height ?? 0,
      })
    }
  })

  return mousePosition$.pipe(
    filter((pos) => pos !== null),
    map((pos) => pos!),
    map((pos) => ({
      x: pos.x - initialPosition.x,
      y: pos.y - initialPosition.y,
    })),
    filter((delta) => Math.sqrt(delta.x ** 2 + delta.y ** 2) > 10 / zoom), // Minimum movement
    map((delta) => ({
      x: delta.x / zoom,
      y: delta.y / zoom,
    })),
    tap((worldDelta) => {
      const updatedNodes = nodes.map((node) => {
        if (selectedIds.has(node.id)) {
          const initialPos = initialNodePositions.get(node.id)!
          const newX = initialPos.x + worldDelta.x
          const newY = initialPos.y + worldDelta.y
          const w = initialPos.width
          const h = initialPos.height
          // Update both selrect and points so logical position stays in sync with visual (frontend does the same)
          const points = [
            { x: newX, y: newY },
            { x: newX + w, y: newY },
            { x: newX + w, y: newY + h },
            { x: newX, y: newY + h },
          ]
          return {
            ...node,
            x: newX,
            y: newY,
            selrect: {
              x1: newX,
              y1: newY,
              x2: newX + w,
              y2: newY + h,
            },
            points,
          }
        }
        return node
      })
      updatePage({ ...page, pageId, children: updatedNodes }).catch(() => {})
    }),
    map(() => undefined),
    takeUntil(stopper)
  )
}
