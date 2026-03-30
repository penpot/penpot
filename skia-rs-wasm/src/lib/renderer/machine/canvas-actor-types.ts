import type { ActorRefFrom } from 'xstate'
import { canvasMachine } from './canvas-machine'

export type CanvasActorRef = ActorRefFrom<typeof canvasMachine>
