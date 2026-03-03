import { describe, it, expect, vi, beforeEach } from 'vitest'
import { translateNodeChange } from './index'
import { SUPPORTED_SCENE_NODE_TYPES } from 'penpot-exporter/plugin'

const mockTransformSceneNode = vi.fn()
const mockTransformId = vi.fn((n: { id: string }) => `penpot-${n.id}`)

vi.mock('penpot-exporter/plugin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('penpot-exporter/plugin')>()
  return {
    ...actual,
    transformSceneNode: (...args: unknown[]) => mockTransformSceneNode(...args),
    transformId: (n: { id: string }) => mockTransformId(n),
  }
})

describe('translateNodeChange', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('returns empty array when nodeChanges is empty', async () => {
    const event = { nodeChanges: [] }
    const result = await translateNodeChange(event as unknown as NodeChangeEvent)
    expect(result).toEqual([])
    expect(mockTransformSceneNode).not.toHaveBeenCalled()
    expect(mockTransformId).not.toHaveBeenCalled()
  })

  it('returns empty array when event has no nodeChanges', async () => {
    const result = await translateNodeChange({} as unknown as NodeChangeEvent)
    expect(result).toEqual([])
  })

  it('emits del-obj for DELETE changes', async () => {
    const event = {
      nodeChanges: [
        {
          type: 'DELETE',
          node: { id: 'figma-123', removed: true, type: 'RECTANGLE' },
        },
      ],
    }
    mockTransformId.mockReturnValue('penpot-123')
    const result = await translateNodeChange(event as unknown as NodeChangeEvent)
    expect(result).toHaveLength(1)
    expect(result[0]).toEqual({ type: 'del-obj', id: 'penpot-123' })
    expect(mockTransformId).toHaveBeenCalledWith({ id: 'figma-123' })
    expect(mockTransformSceneNode).not.toHaveBeenCalled()
  })

  it('includes pageId on del-obj when options.pageId provided', async () => {
    const event = {
      nodeChanges: [
        { type: 'DELETE', node: { id: 'figma-1', removed: true, type: 'FRAME' } },
      ],
    }
    mockTransformId.mockReturnValue('penpot-1')
    const result = await translateNodeChange(event as unknown as NodeChangeEvent, {
      pageId: 'page-uuid',
    })
    expect(result[0]).toMatchObject({ type: 'del-obj', id: 'penpot-1', pageId: 'page-uuid' })
  })

  it('skips CREATE when node type is not in SUPPORTED_SCENE_NODE_TYPES', async () => {
    const event = {
      nodeChanges: [
        {
          type: 'CREATE',
          node: {
            id: 'figma-1',
            parent: { id: 'parent-1', type: 'FRAME', children: [] },
            type: 'PAGE',
          },
        },
      ],
    }
    const result = await translateNodeChange(event as unknown as NodeChangeEvent)
    expect(result).toHaveLength(0)
    expect(mockTransformSceneNode).not.toHaveBeenCalled()
  })

  it('uses exporter supported node types (PAGE is unsupported)', () => {
    expect(SUPPORTED_SCENE_NODE_TYPES.has('PAGE')).toBe(false)
    expect(SUPPORTED_SCENE_NODE_TYPES.has('RECTANGLE')).toBe(true)
  })

  it('emits add-obj for CREATE when transformSceneNode returns drawable shape', async () => {
    const childNode = {
      id: 'figma-2',
      type: 'RECTANGLE',
    }
    const parent = {
      id: 'parent-figma',
      type: 'FRAME',
      children: [{ id: 'figma-1' }, childNode],
    }
    ;(childNode as unknown as { parent: unknown }).parent = parent
    const event = {
      nodeChanges: [
        {
          type: 'CREATE',
          node: childNode,
        },
      ],
    }
    const penpotNode = {
      type: 'rect',
      id: 'penpot-2',
      name: 'Rect',
    }
    mockTransformSceneNode.mockResolvedValue(penpotNode)
    mockTransformId.mockImplementation((n: { id: string }) => `penpot-${n.id}`)
    const result = await translateNodeChange(event as unknown as NodeChangeEvent)
    expect(result).toHaveLength(1)
    expect(result[0].type).toBe('add-obj')
    expect((result[0] as { id: string; obj: unknown }).id).toBe('penpot-2')
    expect((result[0] as { obj: unknown }).obj).toEqual(penpotNode)
    expect((result[0] as { parentId: string }).parentId).toBe('penpot-parent-figma')
  })

  it('emits mod-obj for PROPERTY_CHANGE when shape is drawable', async () => {
    const event = {
      nodeChanges: [
        {
          type: 'PROPERTY_CHANGE',
          properties: ['name'],
          node: { id: 'figma-1', type: 'RECTANGLE', name: 'Updated' },
        },
      ],
    }
    const penpotNode = {
      type: 'rect',
      id: 'penpot-1',
      name: 'Updated',
    }
    mockTransformSceneNode.mockResolvedValue(penpotNode)
    mockTransformId.mockReturnValue('penpot-1')
    const result = await translateNodeChange(event as unknown as NodeChangeEvent)
    expect(result).toHaveLength(1)
    expect(result[0].type).toBe('mod-obj')
    expect((result[0] as { id: string }).id).toBe('penpot-1')
    expect((result[0] as { operations: unknown[] }).operations[0]).toMatchObject({
      type: 'assign',
      value: penpotNode,
    })
  })

  it('skips PROPERTY_CHANGE when node type is not in SUPPORTED_SCENE_NODE_TYPES', async () => {
    const event = {
      nodeChanges: [
        {
          type: 'PROPERTY_CHANGE',
          properties: ['name'],
          node: { id: 'figma-1', type: 'PAGE' },
        },
      ],
    }
    const result = await translateNodeChange(event as unknown as NodeChangeEvent)
    expect(result).toHaveLength(0)
    expect(mockTransformSceneNode).not.toHaveBeenCalled()
  })
})
