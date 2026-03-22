import { describe, expect, it } from 'vitest'
import { u32ToUUID } from './conversions'

describe('u32ToUUID', () => {
  it('pads short buffers to four limbs (no literal undefined in output)', () => {
    const three = new Uint32Array([0xbffac645, 0x800780ca, 0xc0ff1cd8])
    const s = u32ToUUID(three)
    expect(s).not.toContain('undefined')
    expect(s).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
    )
  })

  it('documents JS coercion that produces an undefinedundefined suffix (not from u32ToUUID)', () => {
    const uuid = '00000000-0000-4000-8000-000000000001'
    expect(uuid + undefined + undefined).toBe(`${uuid}undefinedundefined`)
  })
})
