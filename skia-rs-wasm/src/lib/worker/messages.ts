/**
 * Message encoding/decoding for worker communication
 * Translated from frontend/src/app/worker/messages.cljs
 */

import type { WorkerMessage, SerializedMessage } from './types'

export function encode(message: WorkerMessage): SerializedMessage {
  const cmd = message.cmd || message.payload?.cmd || ''
  const cmdName = typeof cmd === 'string' ? cmd : cmd

  return {
    cmd: cmdName,
    replyTo: message.replyTo,
    payload: message.payload,
    buffer: message.buffer,
  }
}

export function decode(data: SerializedMessage): WorkerMessage {
  const cmd = data.cmd
  const replyTo = data.replyTo
  const payload = data.payload
  const buffer = data.buffer

  const result: WorkerMessage = {
    cmd,
    replyTo: replyTo ?? '',
  }

  if (payload) {
    result.payload = payload
  }

  if (buffer !== undefined) {
    result.buffer = buffer
  }

  return result
}

