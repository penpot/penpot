/**
 * Worker factory that creates a Web Worker using Vite's worker import
 * Uses Vite's ?worker suffix to properly bundle and resolve worker dependencies
 */

import { WorkerClient } from './worker-client';
import worker from './worker/index.ts?worker'

/**
 * Creates a Web Worker from the library's worker code
 * Uses Vite's ?worker import syntax which properly handles module resolution
 * @returns Promise that resolves to object with worker instance and cleanup function
 */
export async function createWorker( onWorkerReady?: (client: WorkerClient) => void): Promise<{ workerClient: WorkerClient; cleanup: () => void }> {
  try {
    
    // Create worker instance
    const workerInstance = new worker()
    // Cleanup function to terminate worker
    const workerCleanup = () => {
      workerInstance.terminate()
    }
    const client = new WorkerClient(workerInstance, workerCleanup)
    
    if (onWorkerReady) {
      client.onMessage(onWorkerReady)
    }

    const cleanup = () => {
      client.destroy()
    }
    
    return { workerClient: client, cleanup }
  } catch (error) {
    throw error
  }
}
