/**
 * Handler dispatcher for worker messages
 * Translated from frontend/src/app/worker/impl.cljs
 */

import type { WorkerMessage } from './types'

export type Handler = (message: WorkerMessage) => any

const handlers = new Map<string, Handler>()

export function registerHandler(cmd: string, handler: Handler): void {
  handlers.set(cmd, handler)
}

export function handler(message: WorkerMessage): any {
  const cmd = message.cmd || (message.payload?.cmd as string) || ''

  const handlerFn = handlers.get(cmd)
  if (handlerFn) {
    return handlerFn(message)
  }

  console.warn('Unexpected message:', message)
  return null
}

// Default handlers
registerHandler('echo', (message) => message)

registerHandler('configure', (message) => {
  const config = message.payload?.config
  if (config) {
    console.info('Configure worker:', Object.keys(config))
    // Configuration would be stored here
  }
  return null
})

