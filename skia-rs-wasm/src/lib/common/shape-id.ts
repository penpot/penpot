/**
 * Canonical shape ids for skia-rs-wasm: Penpot v8 UUIDs (see uuid-impl `v8`).
 * All dev-created nodes must use `newShapeId()`; `applyChanges` rejects malformed `add-obj` ids.
 */

import type { AddObjChange } from 'penpot-exporter/types'
import { v8 } from './uuid-impl'

/** RFC 4122 string form: 8-4-4-4-12 lowercase hex with dashes only (36 chars). */
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isCanonicalShapeId(id: string | null | undefined): boolean {
  return typeof id === 'string' && id.length === 36 && CANONICAL_UUID.test(id)
}

/** New shape / object id — always Penpot-compatible v8 (time-ordered, matches frontend). */
export function newShapeId(): string {
  return v8()
}

export function assertValidAddObjChange(change: AddObjChange): void {
  const { id } = change
  if (!isCanonicalShapeId(id)) {
    throw new Error(
      `[skia-rs-wasm] add-obj id must be a canonical 36-char UUID (Penpot v8). Got length=${id?.length ?? 0}: ${String(id).slice(0, 80)}`
    )
  }
  const oid = (change.obj as { id?: string }).id
  if (oid != null && oid !== id) {
    throw new Error(
      `[skia-rs-wasm] add-obj change.id and obj.id must match. change.id=${id} obj.id=${oid}`
    )
  }
}
