/**
 * RxJS streams for user interactions
 * Ported from frontend/src/app/main/streams.cljs
 */

import { BehaviorSubject, fromEvent, merge } from 'rxjs'
import { share } from 'rxjs/operators'
import type { Point } from '../viewport'

// Mouse position stream (BehaviorSubject to hold current value)
export const mousePosition$ = new BehaviorSubject<Point | null>(null)

// Keyboard modifier streams
export const mousePositionShift$ = new BehaviorSubject<boolean>(false)
export const mousePositionAlt$ = new BehaviorSubject<boolean>(false)
export const mousePositionMod$ = new BehaviorSubject<boolean>(false) // Ctrl/Cmd
export const keyboardSpace$ = new BehaviorSubject<boolean>(false)

// Pointer events stream
export const pointerEvents$ = merge(
  fromEvent<PointerEvent>(window, 'pointermove'),
  fromEvent<PointerEvent>(window, 'pointerdown'),
  fromEvent<PointerEvent>(window, 'pointerup')
).pipe(share())


