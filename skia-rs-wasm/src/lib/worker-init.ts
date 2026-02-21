/**
 * Public API for Worker initialization
 * Encapsulates init-in-progress guard and AbortController; updates store with worker client.
 */

import type { WorkerClient } from '@skia-rs-wasm/common'
import { createWorker } from './worker-factory'
import { useWorkspaceStore } from './renderer/store/workspace-store'

export class WorkerClientManager {
  private abortController: AbortController | null = null
  private isInitializing = false
  private initPromise: Promise<WorkerClient> | null = null

  async init(): Promise<WorkerClient> {
    const { workerClient } = useWorkspaceStore.getState()

    if (workerClient) {
      return workerClient
    }

    if (this.isInitializing && this.initPromise) {
      return this.initPromise
    }

    this.abortController = new AbortController()
    const signal = this.abortController.signal
    this.isInitializing = true
    this.initPromise = this.doInit(signal)

    try {
      const client = await this.initPromise
      this.initPromise = null
      this.isInitializing = false
      this.abortController = null
      return client
    } catch (error) {
      this.initPromise = null
      this.isInitializing = false
      this.abortController = null
      throw error
    }
  }

  private async doInit(signal: AbortSignal): Promise<WorkerClient> {
    const { workerClient } = await createWorker()

    if (signal.aborted) {
      workerClient.destroy()
      throw new Error('Worker initialization aborted')
    }

    useWorkspaceStore.getState().setWorkerClient(workerClient)
    return workerClient
  }

  cleanup(): void {
    const { workerClient } = useWorkspaceStore.getState()

    if (this.abortController) {
      this.abortController.abort()
      this.abortController = null
    }

    if (workerClient) {
      workerClient.destroy()
    }

    useWorkspaceStore.getState().setWorkerClient(null)
    this.initPromise = null
    this.isInitializing = false
  }
}

const workerClientManager = new WorkerClientManager()

export function initWorker(): Promise<WorkerClient> {
  return workerClientManager.init()
}

/**
 * Check if worker is ready (initialized in store)
 */
export function isWorkerReady(): boolean {
  return useWorkspaceStore.getState().workerClient !== null
}

/**
 * Get worker client from store
 */
export function getWorkerClient(): WorkerClient | null {
  return useWorkspaceStore.getState().workerClient
}

/**
 * Cleanup worker (terminates and clears from store)
 */
export function cleanupWorker(): void {
  workerClientManager.cleanup()
}
