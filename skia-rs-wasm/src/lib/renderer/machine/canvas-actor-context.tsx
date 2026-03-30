/**
 * React context for the canvas interaction actor (XState machine).
 * Provider is mounted by `CanvasWrapper`; pass sibling UI via `CanvasWrapper`’s `overlays` prop when it must call `useCanvasActor`.
 *
 * Only exports components (Provider + hook) so this file is a valid React Fast Refresh boundary.
 * The `CanvasActorRef` type lives in `canvas-actor-types.ts`.
 */

import { createContext, useContext, type ReactNode } from 'react'
import type { CanvasActorRef } from './canvas-actor-types'

const CanvasActorContext = createContext<CanvasActorRef | null>(null)

export function useCanvasActor(): CanvasActorRef {
  const ref = useContext(CanvasActorContext)
  if (ref == null) {
    throw new Error('useCanvasActor must be used within CanvasActorProvider')
  }
  return ref
}

export function CanvasActorProvider({
  actorRef,
  children,
}: {
  actorRef: CanvasActorRef
  children: ReactNode
}) {
  return <CanvasActorContext.Provider value={actorRef}>{children}</CanvasActorContext.Provider>
}
