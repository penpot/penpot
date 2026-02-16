/**
 * Worker observable wrappers
 * Converts WorkerClient promises to RxJS observables
 */

import { from, Observable } from 'rxjs'
import { bufferTime, map, filter, switchMap } from 'rxjs/operators'
import type { WorkerClient } from '../types'

export function askWorker$(
  workerClient: WorkerClient,
  cmd: string,
  payload?: any
): Observable<any> {
  return from(workerClient.sendMessage(cmd, payload))
}

export function askWorkerBuffered$(
  workerClient: WorkerClient,
  cmd: string,
  payload$: Observable<any>
): Observable<any> {
  return payload$.pipe(
    bufferTime(100),
    map(buffer => buffer[buffer.length - 1]),
    filter(val => val !== undefined),
    switchMap(payload => askWorker$(workerClient, cmd, payload))
  )
}


