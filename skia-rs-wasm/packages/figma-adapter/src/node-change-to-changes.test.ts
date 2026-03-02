import { describe, it, expect } from 'vitest'
import {
  buildDelObjChange,
  buildAddObjChange,
  buildModObjChange,
} from './node-change-to-changes'
import type { PenpotNode } from 'penpot-exporter/lib'

describe('node-change-to-changes', () => {
  describe('buildDelObjChange', () => {
    it('returns del-obj with id', () => {
      const c = buildDelObjChange('id-1')
      expect(c.type).toBe('del-obj')
      expect(c.id).toBe('id-1')
    })

    it('includes pageId when provided', () => {
      const c = buildDelObjChange('id-1', 'page-1')
      expect(c.pageId).toBe('page-1')
    })
  })

  describe('buildAddObjChange', () => {
    it('returns add-obj with required fields', () => {
      const obj = { type: 'rect', id: 'r1', name: 'Rect' } as PenpotNode
      const c = buildAddObjChange('r1', obj, 'parent-1', 'frame-1', 0)
      expect(c.type).toBe('add-obj')
      expect(c.id).toBe('r1')
      expect(c.obj).toBe(obj)
      expect(c.parentId).toBe('parent-1')
      expect(c.frameId).toBe('frame-1')
      expect(c.index).toBe(0)
    })

    it('includes pageId when provided', () => {
      const obj = { type: 'rect', id: 'r1', name: 'Rect' } as PenpotNode
      const c = buildAddObjChange('r1', obj, 'p', 'f', null, 'page-1')
      expect(c.pageId).toBe('page-1')
    })
  })

  describe('buildModObjChange', () => {
    it('returns mod-obj with assign operation', () => {
      const c = buildModObjChange('id-1', { name: 'New' })
      expect(c.type).toBe('mod-obj')
      expect(c.id).toBe('id-1')
      expect(c.operations).toHaveLength(1)
      expect(c.operations[0].type).toBe('assign')
      expect((c.operations[0] as { value: Record<string, unknown> }).value).toEqual({ name: 'New' })
    })

    it('includes pageId when provided', () => {
      const c = buildModObjChange('id-1', {}, 'page-1')
      expect(c.pageId).toBe('page-1')
    })
  })
})
