/**
 * Handler dispatcher for worker messages
 * Translated from frontend/src/app/worker/impl.cljs
 */

import type { WorkerMessage } from './types'

export type Handler = (message: WorkerMessage) => unknown

const handlers = new Map<string, Handler>()

export function registerHandler(cmd: string, handler: Handler): void {
  handlers.set(cmd, handler)
}

export function handler(message: WorkerMessage): unknown {
  const cmd = message.cmd || (typeof message.payload === 'object' && message.payload !== null && 'cmd' in message.payload ? String((message.payload as Record<string, unknown>).cmd) : '') || ''

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
  const config = typeof message.payload === 'object' && message.payload !== null && 'config' in message.payload ? (message.payload as Record<string, unknown>).config : undefined
  if (config) {
    console.info('Configure worker:', Object.keys(config))
    // Configuration would be stored here
  }
  return null
})

