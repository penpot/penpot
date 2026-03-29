/**
 * Per-frame pointer and modifier state (see docs/state-architecture.md §3).
 * Handlers read `.value`; React does not subscribe here for these hot paths.
 */

import { computed, effect, signal } from '@preact/signals-core'
import type { Signal } from '@preact/signals-core'
import { Observable } from 'rxjs'
import type { Point } from '../types'
import type { ViewportData } from '../viewport'
import { screenToWorld } from '../viewport'

export const pointerPos = signal<Point | null>(null)

export const modShift = signal(false)
export const modAlt = signal(false)
export const modCtrl = signal(false)
export const modMeta = signal(false)

export const keyboardSpace = signal(false)

/** Mirrors workspace `viewport`; kept in sync inside `updateViewport`. */
export const viewportSignal = signal<ViewportData | null>(null)

export const worldPointerPos = computed(() => {
  const pos = pointerPos.value
  const vp = viewportSignal.value
  if (!pos || !vp) return null
  return screenToWorld(vp, pos.x, pos.y)
})

/** Bridge for XState `fromObservable` drag pipelines that still use RxJS operators. */
export function signalToObservable<T>(sig: Signal<T>): Observable<T> {
  return new Observable((subscriber) => {
    const dispose = effect(() => {
      subscriber.next(sig.value)
    })
    return () => dispose()
  })
}
