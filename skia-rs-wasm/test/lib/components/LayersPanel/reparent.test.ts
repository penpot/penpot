import { describe, expect, it } from 'vitest'
import type { IndexedPage, IndexedShape } from '../../../../src/lib/worker/types'
import {
  buildReparentChanges,
  computeDropSide,
  findContainerAtPoint,
  isAncestor,
  isContainer,
  resolveDropTarget,
} from '../../../../src/lib/components/LayersPanel/reparent'
import { processChanges } from '../../../../src/lib/worker/process-changes'

const PAGE_ID = 'page1'
const ROOT = '00000000-0000-0000-0000-000000000000'

function emptySel() {
  return { x: 0, y: 0, width: 1, height: 1, x1: 0, y1: 0, x2: 1, y2: 1 }
}

function emptyPoints() {
  return [
    { x: 0, y: 0 },
    { x: 1, y: 0 },
    { x: 1, y: 1 },
    { x: 0, y: 1 },
  ]
}

function mkShape(partial: Partial<IndexedShape> & { id: string; type: string }): IndexedShape {
  return {
    name: partial.id,
    x: 0,
    y: 0,
    width: 1,
    height: 1,
    selrect: emptySel(),
    points: emptyPoints(),
    ...partial,
  } as IndexedShape
}

/**
 * Fixture:
 *   ROOT (frame)
 *   ├── FRAME1 (frame)
 *   │   ├── RECT_A (rect)
 *   │   └── RECT_B (rect)
 *   ├── GROUP1 (group)
 *   │   └── RECT_C (rect)
 *   └── RECT_D (rect)
 */
function makePage(): IndexedPage {
  const objects: Record<string, IndexedShape> = {
    [ROOT]: mkShape({
      id: ROOT,
      type: 'frame',
      shapes: ['frame1', 'group1', 'rectD'],
    }),
    frame1: mkShape({
      id: 'frame1',
      type: 'frame',
      parentId: ROOT,
      frameId: ROOT,
      shapes: ['rectA', 'rectB'],
    }),
    group1: mkShape({
      id: 'group1',
      type: 'group',
      parentId: ROOT,
      frameId: ROOT,
      shapes: ['rectC'],
    }),
    rectA: mkShape({ id: 'rectA', type: 'rect', parentId: 'frame1', frameId: 'frame1' }),
    rectB: mkShape({ id: 'rectB', type: 'rect', parentId: 'frame1', frameId: 'frame1' }),
    rectC: mkShape({ id: 'rectC', type: 'rect', parentId: 'group1', frameId: ROOT }),
    rectD: mkShape({ id: 'rectD', type: 'rect', parentId: ROOT, frameId: ROOT }),
  }
  return { id: PAGE_ID, objects }
}

describe('computeDropSide', () => {
  const H = 100

  it('detects three zones when detectCenter=true', () => {
    expect(computeDropSide(10, H, true)).toBe('top')
    expect(computeDropSide(50, H, true)).toBe('center')
    expect(computeDropSide(90, H, true)).toBe('bot')
  })

  it('splits 50/50 when detectCenter=false', () => {
    expect(computeDropSide(40, H, false)).toBe('top')
    expect(computeDropSide(60, H, false)).toBe('bot')
  })

  it('handles degenerate row height', () => {
    expect(computeDropSide(0, 0, true)).toBe('bot')
  })
})

describe('isContainer', () => {
  it('accepts frame/group/bool/component', () => {
    expect(isContainer({ type: 'frame' } as IndexedShape)).toBe(true)
    expect(isContainer({ type: 'group' } as IndexedShape)).toBe(true)
    expect(isContainer({ type: 'bool' } as IndexedShape)).toBe(true)
    expect(isContainer({ type: 'component' } as IndexedShape)).toBe(true)
  })

  it('rejects leaves', () => {
    expect(isContainer({ type: 'rect' } as IndexedShape)).toBe(false)
    expect(isContainer({ type: 'text' } as IndexedShape)).toBe(false)
    expect(isContainer(null)).toBe(false)
  })
})

describe('isAncestor', () => {
  it('walks parent chain', () => {
    const { objects } = makePage()
    expect(isAncestor(objects, 'frame1', 'rectA')).toBe(true)
    expect(isAncestor(objects, ROOT, 'rectA')).toBe(true)
    expect(isAncestor(objects, 'frame1', 'frame1')).toBe(true)
    expect(isAncestor(objects, 'group1', 'rectA')).toBe(false)
    expect(isAncestor(objects, 'rectA', 'frame1')).toBe(false)
  })
})

describe('resolveDropTarget', () => {
  it('center on container → (targetId, 0)', () => {
    const { objects } = makePage()
    expect(
      resolveDropTarget({ targetId: 'group1', side: 'center', draggedIds: ['rectD'], objects }),
    ).toEqual({ parentId: 'group1', index: 0 })
  })

  it('center on leaf → null', () => {
    const { objects } = makePage()
    expect(
      resolveDropTarget({ targetId: 'rectA', side: 'center', draggedIds: ['rectD'], objects }),
    ).toBeNull()
  })

  it('top/bot on sibling → correct parent + index', () => {
    const { objects } = makePage()
    // rectB is at index 1 in frame1 — dropping rectD above rectB inserts at 1; below at 2.
    expect(
      resolveDropTarget({ targetId: 'rectB', side: 'top', draggedIds: ['rectD'], objects }),
    ).toEqual({ parentId: 'frame1', index: 1 })
    expect(
      resolveDropTarget({ targetId: 'rectB', side: 'bot', draggedIds: ['rectD'], objects }),
    ).toEqual({ parentId: 'frame1', index: 2 })
  })

  it('drop on self → null', () => {
    const { objects } = makePage()
    expect(
      resolveDropTarget({ targetId: 'rectA', side: 'top', draggedIds: ['rectA'], objects }),
    ).toBeNull()
    expect(
      resolveDropTarget({ targetId: 'frame1', side: 'center', draggedIds: ['frame1'], objects }),
    ).toBeNull()
  })

  it('drop into descendant → null', () => {
    const { objects } = makePage()
    expect(
      resolveDropTarget({ targetId: 'rectA', side: 'top', draggedIds: ['frame1'], objects }),
    ).toBeNull()
    expect(
      resolveDropTarget({ targetId: 'frame1', side: 'center', draggedIds: [ROOT], objects }),
    ).toBeNull()
  })

  it('no-op drop (same parent + current index) → null', () => {
    const { objects } = makePage()
    expect(
      resolveDropTarget({ targetId: 'rectB', side: 'top', draggedIds: ['rectA'], objects }),
    ).toBeNull()
    expect(
      resolveDropTarget({ targetId: 'rectA', side: 'bot', draggedIds: ['rectB'], objects }),
    ).toBeNull()
  })
})

describe('buildReparentChanges', () => {
  it('redo is a single mov-objects change', () => {
    const { objects } = makePage()
    const { redoChanges } = buildReparentChanges({
      pageId: PAGE_ID,
      parentId: 'group1',
      index: 0,
      shapeIds: ['rectD'],
      objects,
    })
    expect(redoChanges).toEqual([
      {
        type: 'mov-objects',
        pageId: PAGE_ID,
        parentId: 'group1',
        shapes: ['rectD'],
        index: 0,
      },
    ])
  })

  it('undo restores shapes grouped by original parent with afterShape', () => {
    const { objects } = makePage()
    const { undoChanges } = buildReparentChanges({
      pageId: PAGE_ID,
      parentId: 'group1',
      index: 1,
      shapeIds: ['rectA', 'rectD'],
      objects,
    })
    // rectA came from frame1 at index 0 → no afterShape, index 0.
    // rectD came from ROOT at index 2 → afterShape = group1.
    const byParent = Object.fromEntries(undoChanges.map((c) => [c.parentId, c]))
    expect(byParent['frame1']).toMatchObject({
      type: 'mov-objects',
      parentId: 'frame1',
      shapes: ['rectA'],
      index: 0,
    })
    expect(byParent[ROOT]).toMatchObject({
      type: 'mov-objects',
      parentId: ROOT,
      shapes: ['rectD'],
      afterShape: 'group1',
    })
  })

  it('redo + undo roundtrip returns to original page', () => {
    const page = makePage()
    const { redoChanges, undoChanges } = buildReparentChanges({
      pageId: PAGE_ID,
      parentId: 'group1',
      index: 1,
      shapeIds: ['rectA'],
      objects: page.objects,
    })
    const afterRedo = processChanges(structuredClone(page), redoChanges)
    expect(afterRedo.objects.rectA.parentId).toBe('group1')
    expect(afterRedo.objects.group1.shapes).toEqual(['rectC', 'rectA'])
    expect(afterRedo.objects.frame1.shapes).toEqual(['rectB'])

    const afterUndo = processChanges(afterRedo, undoChanges)
    expect(afterUndo.objects.rectA.parentId).toBe('frame1')
    expect(afterUndo.objects.frame1.shapes).toEqual(['rectA', 'rectB'])
    expect(afterUndo.objects.group1.shapes).toEqual(['rectC'])
  })
})

describe('findContainerAtPoint', () => {
  const sel = (x: number, y: number, w: number, h: number) => ({
    x, y, width: w, height: h, x1: x, y1: y, x2: x + w, y2: y + h,
  })

  /**
   * Fixture: outer frame (0,0,200,200) with an inner frame (50,50,80,80).
   * Plus a loose rect (10,10,20,20) outside the inner frame.
   */
  function makeFrameScene(): Record<string, IndexedShape> {
    return {
      outer: mkShape({ id: 'outer', type: 'frame', selrect: sel(0, 0, 200, 200), shapes: ['inner', 'loose'] }),
      inner: mkShape({ id: 'inner', type: 'frame', parentId: 'outer', frameId: 'outer', selrect: sel(50, 50, 80, 80) }),
      loose: mkShape({ id: 'loose', type: 'rect', parentId: 'outer', frameId: 'outer', selrect: sel(10, 10, 20, 20) }),
    }
  }

  it('returns the innermost containing frame', () => {
    const objects = makeFrameScene()
    expect(findContainerAtPoint(objects, { x: 80, y: 80 }, [])).toBe('inner')
    expect(findContainerAtPoint(objects, { x: 5, y: 5 }, [])).toBe('outer')
  })

  it('returns null when point is outside all frames', () => {
    const objects = makeFrameScene()
    expect(findContainerAtPoint(objects, { x: 500, y: 500 }, [])).toBeNull()
  })

  it('excludes dragged shapes from being a target (self-drop)', () => {
    const objects = makeFrameScene()
    // Center of outer is (100,100) which also lies inside inner — excluding inner
    // forces the fallback to outer.
    expect(findContainerAtPoint(objects, { x: 100, y: 100 }, ['inner'])).toBe('outer')
  })

  it('excludes descendants of dragged shapes', () => {
    const objects = makeFrameScene()
    // Excluding outer also excludes inner (descendant) — point (80,80) → null.
    expect(findContainerAtPoint(objects, { x: 80, y: 80 }, ['outer'])).toBeNull()
  })

  it('ignores non-containers', () => {
    const objects = makeFrameScene()
    // Point inside loose rect but rect isn't a container — fallback to outer.
    expect(findContainerAtPoint(objects, { x: 15, y: 15 }, [])).toBe('outer')
  })
})
