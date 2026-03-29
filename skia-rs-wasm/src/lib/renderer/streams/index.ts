/**
 * RxJS streams for user interactions
 * Ported from frontend/src/app/main/streams.cljs
 */

import { fromEvent, merge } from 'rxjs'
import { share } from 'rxjs/operators'

// Pointer events stream
export const pointerEvents$ = merge(
  fromEvent<PointerEvent>(window, 'pointermove'),
  fromEvent<PointerEvent>(window, 'pointerdown'),
  fromEvent<PointerEvent>(window, 'pointerup')
).pipe(share())
