import { useEffect, useState } from 'react'
import { effect } from '@preact/signals-core'
import type { Signal } from '@preact/signals-core'

/** Batches signal updates to one React state commit per animation frame. */
export function useSignalCoalesced<T>(sig: Signal<T>): T {
  const [value, setValue] = useState(() => sig.peek())
  useEffect(() => {
    let raf = 0
    const dispose = effect(() => {
      const v = sig.value
      if (raf) return
      raf = requestAnimationFrame(() => {
        raf = 0
        setValue(() => v)
      })
    })
    return () => {
      dispose()
      if (raf) cancelAnimationFrame(raf)
    }
  }, [sig])
  return value
}
