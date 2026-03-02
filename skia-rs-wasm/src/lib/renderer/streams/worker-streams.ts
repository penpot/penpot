/**
 * Worker observable wrappers
 * Converts WorkerClient promises to RxJS observables
 */

import { from, Observable } from 'rxjs'
import { bufferTime, map, filter, switchMap } from 'rxjs/operators'
import type { WorkerClient, WorkerResponse, WorkerSendPayload } from '../../worker/types'

export function askWorker$(
  workerClient: WorkerClient,
  cmd: string,
  payload?: WorkerSendPayload
): Observable<WorkerResponse> {
  return from(workerClient.sendMessage(cmd, payload))
}

export function askWorkerBuffered$(
  workerClient: WorkerClient,
  cmd: string,
  payload$: Observable<WorkerSendPayload>
): Observable<WorkerResponse> {
  return payload$.pipe(
    bufferTime(100),
    map(buffer => buffer[buffer.length - 1]),
    filter((val): val is Exclude<WorkerSendPayload, undefined> => val !== undefined),
    switchMap(payload => askWorker$(workerClient, cmd, payload))
  )
}


