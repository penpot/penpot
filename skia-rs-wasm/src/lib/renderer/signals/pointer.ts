/**
 * Per-frame pointer, modifier, and drag-preview state (see docs/state-architecture.md §3).
 * Handlers write `.value` at pointer rate; React reads hot values via `useSignalCoalesced`.
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

/** Canonical pan/zoom for the canvas; writers set `.value` (see `canvas-wrapper`, `viewport-actions`). */
export const viewport = signal<ViewportData | null>(null)

/** Delta (deg) during active rotate drag; property panel reads via `useSignalCoalesced`. */
export const rotatePreviewDeltaDeg = signal(0)

/** World-space translation during move drag; property panel reads via `useSignalCoalesced`. */
export const movePreviewWorldDelta = signal<Point>({ x: 0, y: 0 })

export const worldPointerPos = computed(() => {
  const pos = pointerPos.value
  const vp = viewport.value
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
