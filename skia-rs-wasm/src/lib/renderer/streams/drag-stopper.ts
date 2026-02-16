/**
 * Drag stopper utility
 * Ported from frontend's mse/drag-stopper
 * Returns an observable that completes when mouse button is released
 */

import { merge, fromEvent, Observable } from 'rxjs'
import { take, map } from 'rxjs/operators'

export function dragStopper(): Observable<void> {
  return merge(
    fromEvent(window, 'mouseup'),
    fromEvent(window, 'pointerup')
  ).pipe(
    take(1),
    map(() => undefined)
  )
}


