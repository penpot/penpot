/* eslint-disable react-refresh/only-export-components -- context module exports Provider + hook */
/**
 * React context for the canvas interaction actor (XState machine).
 * Provider is mounted by `CanvasWrapper`; pass sibling UI via `CanvasWrapper`’s `overlays` prop when it must call `useCanvasActor`.
 */

import { createContext, useContext, type ReactNode } from 'react'
import type { ActorRefFrom } from 'xstate'
import { canvasMachine } from './canvas-machine'

export type CanvasActorRef = ActorRefFrom<typeof canvasMachine>

export const CanvasActorContext = createContext<CanvasActorRef | null>(null)

export function useCanvasActor(): CanvasActorRef {
  const ref = useContext(CanvasActorContext)
  if (ref == null) {
    throw new Error('useCanvasActor must be used within CanvasActorContext.Provider')
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
