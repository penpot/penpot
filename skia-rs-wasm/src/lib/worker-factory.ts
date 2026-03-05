/**
 * Worker factory that creates a Web Worker using Vite's worker import or a script URL.
 * When workerScriptUrl is provided (e.g. plugin context), uses that; otherwise uses the bundled ?worker.
 */

import { WorkerClient } from './worker-client'
import worker from './worker/index.ts?worker'

/**
 * Creates a Web Worker from the library's worker code or from a script URL.
 * @param workerScriptUrl - Optional URL to the worker script (e.g. when consumed by Figma plugin build).
 * @param onWorkerReady - Optional callback when the worker client is ready.
 * @returns Promise that resolves to object with worker instance and cleanup function.
 */
export async function createWorker(
  workerScriptUrl?: string,
  onWorkerReady?: (client: WorkerClient) => void
): Promise<{ workerClient: WorkerClient; cleanup: () => void }> {
  const workerInstance = workerScriptUrl
    ? new Worker(workerScriptUrl, { type: 'module' })
    : new worker()
  const workerCleanup = () => {
    workerInstance.terminate()
  }
  const client = new WorkerClient(workerInstance, workerCleanup)

  if (onWorkerReady) {
    onWorkerReady(client)
  }

  const cleanup = () => {
    client.destroy()
  }

  return { workerClient: client, cleanup }
}
