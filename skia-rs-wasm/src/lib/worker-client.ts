/**
 * Worker client for typed, promise-based communication with the worker
 */

import type { PenpotPage } from '@penpot-exporter/types'
import { ensurePageShapePoints } from './renderer/store/ensure-shape-points'
import type {
  WorkerMessage,
  SerializedMessage,
  WorkerClient as WorkerClientInterface,
  WorkerConfig,
  WorkerResponse,
  WorkerSendPayload,
  Change,
} from '@skia-rs-wasm/common'
import { encode, decode } from './worker/messages'

/**
 * Worker client that provides promise-based communication
 */
export class WorkerClient implements WorkerClientInterface {
  private worker: Worker
  private cleanup: () => void
  private pendingRequests = new Map<
    string,
    { resolve: (value: WorkerResponse) => void; reject: (error: Error) => void }
  >()
  private messageListeners = new Set<(message: WorkerMessage) => void>()
  private nextId = 0

  constructor(worker: Worker, cleanup: () => void) {
    this.worker = worker
    this.cleanup = cleanup

    // Set up message listener
    this.worker.addEventListener('message', this.handleMessage)
    this.worker.addEventListener('error', this.handleError)
  }

  /**
   * Send a message to the worker and wait for response
   */
  sendMessage(cmd: string, payload?: WorkerSendPayload): Promise<WorkerResponse> {
    return new Promise((resolve, reject) => {
      const replyTo = this.generateReplyTo()

      const message: WorkerMessage = {
        cmd,
        replyTo,
        payload,
      }

      // Store pending request
      this.pendingRequests.set(replyTo, { resolve, reject })

      // Send message
      try {
        const encoded = encode(message)
        this.worker.postMessage(encoded)
      } catch (error) {
        this.pendingRequests.delete(replyTo)
        reject(error instanceof Error ? error : new Error(String(error)))
      }
    })
  }

  /**
   * Register a message listener
   * @returns Unsubscribe function
   */
  onMessage(callback: (message: WorkerMessage) => void): () => void {
    this.messageListeners.add(callback)
    return () => {
      this.messageListeners.delete(callback)
    }
  }

  /**
   * Configure the worker
   */
  async configure(config: WorkerConfig): Promise<void> {
    await this.sendMessage('configure', { config })
  }

  /**
   * Add a single page to the worker index
   */
  async addPage(page: PenpotPage): Promise<void> {
    const normalized = ensurePageShapePoints(page)
    await this.sendMessage('index/initialize', { page: normalized })
  }

  /**
   * Update an existing page in the worker index (full page replacement)
   */
  async updatePage(pageId: string, page: PenpotPage): Promise<void> {
    const normalized = ensurePageShapePoints(page)
    await this.sendMessage('index/update', { pageId, page: normalized })
  }

  /**
   * Update an existing page in the worker index using incremental changes
   */
  async updatePageWithChanges(pageId: string, changes: Change[]): Promise<void> {
    await this.sendMessage('index/update', { pageId, changes })
  }

  /**
   * Handle incoming messages from worker
   */
  private handleMessage = (event: MessageEvent) => {
    try {
      // Decode the message from worker (worker sends encoded messages)
      // Error messages might be sent directly without encoding, so handle both cases
      let decoded: WorkerMessage
      let rawData = event.data as any

      // Check if this is an error message sent directly (not encoded)
      if (rawData.cmd === 'error' && rawData.error !== undefined) {
        // Error message sent directly without encoding
        const errorMessage = rawData.error?.message || rawData.error || 'Worker error'
        const replyTo = rawData.replyTo
        if (replyTo) {
          const pending = this.pendingRequests.get(replyTo)
          if (pending) {
            this.pendingRequests.delete(replyTo)
            pending.reject(new Error(errorMessage))
            return
          }
        }
        for (const [id, { reject }] of this.pendingRequests) {
          this.pendingRequests.delete(id)
          reject(new Error(errorMessage))
        }
        return
      }
      
      // Decode the message (worker sends encoded SerializedMessage)
      decoded = decode(rawData as SerializedMessage)
      
      // Check if this is a response to a pending request
      if (decoded.replyTo) {
        const pending = this.pendingRequests.get(decoded.replyTo)
        if (pending) {
          this.pendingRequests.delete(decoded.replyTo)
          pending.resolve(decoded.payload as WorkerResponse)
          return
        }
      }

      // Notify all message listeners with decoded message
      for (const listener of this.messageListeners) {
        listener(decoded)
      }
    } catch (error) {
      console.error('Error handling worker message:', error)
    }
  }

  /**
   * Handle worker errors
   */
  private handleError = (event: ErrorEvent) => {
    // Build comprehensive error message
    const errorParts: string[] = []
    
    if (event.error) {
      // If we have an Error object, extract its details
      if (event.error instanceof Error) {
        errorParts.push(`Error: ${event.error.message}`)
        if (event.error.stack) {
          errorParts.push(`Stack: ${event.error.stack}`)
        }
      } else {
        errorParts.push(`Error: ${String(event.error)}`)
      }
    } else if (event.message) {
      errorParts.push(`Message: ${event.message}`)
    }
    
    // Add location information if available
    if (event.filename) {
      errorParts.push(`File: ${event.filename}`)
    }
    if (event.lineno) {
      errorParts.push(`Line: ${event.lineno}`)
    }
    if (event.colno) {
      errorParts.push(`Column: ${event.colno}`)
    }
    
    // Extract additional information from the event and worker
    const eventTarget = event.target as Worker | null
    if (eventTarget) {
      // Try to get worker script URL if available
      if ('scriptURL' in eventTarget && eventTarget.scriptURL) {
        errorParts.push(`Worker script: ${eventTarget.scriptURL}`)
      }
      // Check if worker is in a specific state
      if ('name' in eventTarget && eventTarget.name) {
        errorParts.push(`Worker name: ${eventTarget.name}`)
      }
    }
    
    // Log event type
    if (event.type) {
      errorParts.push(`Event type: ${event.type}`)
    }
    
    // Log the comprehensive error
    const errorMessage = errorParts.length > 0 
      ? errorParts.join(', ') 
      : 'Unknown worker error'
    
    console.error('Worker error:', errorMessage)
    
    // Log the full event object and all its properties for debugging
    console.error('Error event details:', {
      error: event.error,
      message: event.message,
      filename: event.filename,
      lineno: event.lineno,
      colno: event.colno,
      type: event.type,
      target: event.target,
      workerScriptURL: eventTarget && 'scriptURL' in eventTarget ? (eventTarget as any).scriptURL : undefined,
      workerName: eventTarget && 'name' in eventTarget ? (eventTarget as any).name : undefined,
    })
    
    // Also log all enumerable properties of the event to catch anything we missed
    console.error('All event properties:', Object.keys(event).reduce((acc, key) => {
      try {
        acc[key] = (event as any)[key]
      } catch (e) {
        acc[key] = '[unable to access]'
      }
      return acc
    }, {} as Record<string, any>))
    
    // Reject all pending requests with a descriptive error
    const errorForRejection = errorParts.length > 0
      ? new Error(`Worker error: ${errorParts.join(', ')}`)
      : new Error('Worker error: Unknown error')
      
    for (const [replyTo, { reject }] of this.pendingRequests) {
      this.pendingRequests.delete(replyTo)
      reject(errorForRejection)
    }
  }

  /**
   * Generate unique replyTo for request-response correlation (client_${timestamp}_${counter})
   */
  private generateReplyTo(): string {
    return `client_${Date.now()}_${this.nextId++}`
  }

  /**
   * Destroy the worker client and cleanup resources
   */
  destroy(): void {
    // Remove event listeners
    this.worker.removeEventListener('message', this.handleMessage)
    this.worker.removeEventListener('error', this.handleError)

    // Reject all pending requests
    for (const [replyTo, { reject }] of this.pendingRequests) {
      this.pendingRequests.delete(replyTo)
      reject(new Error('Worker client destroyed'))
    }
    this.pendingRequests.clear()

    // Clear message listeners
    this.messageListeners.clear()

    // Cleanup worker (terminate and revoke blob URL)
    this.cleanup()
  }
}

