/**
 * Selection handler
 * Ported from frontend/src/app/main/data/workspace/selection.cljs handle-area-selection
 */

import { Observable, concat, merge, of, EMPTY } from 'rxjs'
import { map, filter, scan, takeUntil, take, bufferTime, tap, distinctUntilChanged, switchMap } from 'rxjs/operators'
import { mousePosition$ } from '../streams'
import { askWorker$ } from '../streams/worker-streams'
import { dragStopper } from '../streams/drag-stopper'
import { useWorkspaceStore } from '../store/workspace-store'
import { makeSelrect } from '../../worker/types'

export function handleAreaSelection(
  append?: boolean,
  remove?: boolean,
  ignoreGroups?: boolean
): Observable<void> {
  const store = useWorkspaceStore.getState()
  const { workerClient, pageId, viewport } = store

  // #region agent log
  fetch('http://127.0.0.1:7244/ingest/f0136137-81f1-4f6e-a7b5-217ac99b12a5',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({location:'selection.ts:handleAreaSelection',message:'area selection start',data:{hasWorker:!!workerClient,hasViewport:!!viewport,pageId:pageId??null},timestamp:Date.now(),hypothesisId:'H4,H5'})}).catch(()=>{});
  // #endregion

  if (!workerClient || !viewport) return EMPTY
  
  const stopper = dragStopper()
  const initialSet = append || remove ? store.selectedIds : new Set<string>()
  
  // Get initial position from current mouse position or wait for first value
  return mousePosition$.pipe(
    filter(pos => pos !== null),
    take(1),
    tap(initialPosition => {
      // #region agent log
      fetch('http://127.0.0.1:7244/ingest/f0136137-81f1-4f6e-a7b5-217ac99b12a5',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({location:'selection.ts:handleAreaSelection',message:'area selection got initial position',data:initialPosition?{x:initialPosition.x,y:initialPosition.y}:null,timestamp:Date.now(),hypothesisId:'H6'})}).catch(()=>{});
      // #endregion
    }),
    switchMap(initialPosition => {
      if (!initialPosition) return EMPTY

      // Calculate selection rectangle stream
      const selrectStream = mousePosition$.pipe(
        filter(pos => pos !== null),
        map(pos => pos!),
        scan((_acc, pos) => {
          // Calculate rect from initial to current position (full Selrect)
          const x1 = Math.min(initialPosition.x, pos.x)
          const y1 = Math.min(initialPosition.y, pos.y)
          const x2 = Math.max(initialPosition.x, pos.x)
          const y2 = Math.max(initialPosition.y, pos.y)
          return makeSelrect(x1, y1, x2 - x1, y2 - y1)
        }, makeSelrect(0, 0, 0, 0)),
        filter(rect => rect.width > 10 || rect.height > 10), // Minimum size
        tap(rect => {
          // #region agent log
          fetch('http://127.0.0.1:7244/ingest/f0136137-81f1-4f6e-a7b5-217ac99b12a5',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({location:'selection.ts:selrectStream',message:'area rect update',data:{w:rect?.width,h:rect?.height},timestamp:Date.now(),hypothesisId:'H6'})}).catch(()=>{});
          // #endregion
        }),
        takeUntil(stopper)
      )
      
      // Convert to world coordinates and query worker
      const queryStream = selrectStream.pipe(
        map(rect => {
          const worldRect = viewport.screenToWorld(rect.x, rect.y)
          return makeSelrect(
            worldRect.x,
            worldRect.y,
            rect.width / viewport.zoom,
            rect.height / viewport.zoom
          )
        }),
        switchMap(rect => 
          askWorker$(workerClient, 'index/query-selection', {
            pageId,
            rect,
            includeFrames: true,
            ignoreGroups: ignoreGroups ?? false,
            fullFrame: true,
            usingSelrect: true
          })
        ),
        map(ids => {
          const newIds = remove 
            ? new Set([...initialSet].filter(id => !ids.includes(id)))
            : new Set([...initialSet, ...ids])
          useWorkspaceStore.getState().setSelectedIds(newIds)
          return ids
        })
      )
      
      return concat(
        append || remove ? EMPTY : of(null).pipe(
          tap(() => useWorkspaceStore.getState().clearSelection()),
          map(() => undefined)
        ),
        merge(
          selrectStream.pipe(
            tap(rect => useWorkspaceStore.getState().setSelectionRect(rect)),
            map(() => undefined)
          ),
          queryStream.pipe(
            bufferTime(100),
            map(buffer => buffer[buffer.length - 1]),
            filter(val => val !== undefined),
            distinctUntilChanged(),
            map(() => undefined)
          )
        ),
        of(null).pipe(
          tap(() => useWorkspaceStore.getState().setSelectionRect(null)),
          map(() => undefined)
        )
      )
    })
  )
}

