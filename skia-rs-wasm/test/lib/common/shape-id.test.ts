import { describe, expect, it } from 'vitest'
import {
  assertValidAddObjChange,
  isCanonicalShapeId,
  newShapeId,
} from '../../../src/lib/common/shape-id'
import { v4 } from '../../../src/lib/common/uuid-impl'
import type { AddObjChange } from 'penpot-exporter/types'

describe('shape-id', () => {
  it('newShapeId and v4 return canonical 36-char UUID (bufferToHex must not read past 16 bytes)', () => {
    const id = newShapeId()
    expect(id.length).toBe(36)
    expect(isCanonicalShapeId(id)).toBe(true)
    expect(v4().length).toBe(36)
  })

  it('assertValidAddObjChange rejects garbage suffix', () => {
    const bad: AddObjChange = {
      type: 'add-obj',
      id: '00000000-0000-4000-8000-000000000001undefinedundefined',
      obj: { type: 'rect', name: 'x' } as AddObjChange['obj'],
      frameId: '00000000-0000-0000-0000-000000000000',
      parentId: '00000000-0000-0000-0000-000000000000',
    }
    expect(() => assertValidAddObjChange(bad)).toThrow(/canonical/)
  })

  it('assertValidAddObjChange accepts v8-shaped id', () => {
    const id = newShapeId()
    const ok: AddObjChange = {
      type: 'add-obj',
      id,
      obj: { type: 'rect', name: 'x', id } as AddObjChange['obj'],
      frameId: '00000000-0000-0000-0000-000000000000',
      parentId: '00000000-0000-0000-0000-000000000000',
    }
    expect(() => assertValidAddObjChange(ok)).not.toThrow()
  })
})
