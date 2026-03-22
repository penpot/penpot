import { describe, expect, it } from 'vitest'
import { processChanges } from '../../../src/lib/worker/process-changes'
import type { IndexedPage } from '../../../src/lib/worker/types'
import {
  appendModObjPair,
  emptyChangesBuilder,
  mergeBundle,
  snapshotGeometryForUndo,
  toCommitBundle,
} from '../../../src/lib/changes/changes-builder'

const ROOT = '00000000-0000-0000-0000-000000000000'
const RECT_A = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const RECT_B = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

function makePage(): IndexedPage {
  const rootSel = { x: 0, y: 0, width: 800, height: 600, x1: 0, y1: 0, x2: 800, y2: 600 }
  const rSel = { x: 0, y: 0, width: 100, height: 50, x1: 0, y1: 0, x2: 100, y2: 50 }
  const rPts = [
    { x: 0, y: 0 },
    { x: 100, y: 0 },
    { x: 100, y: 50 },
    { x: 0, y: 50 },
  ]
  const t = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 }
  return {
    id: 'page1',
    objects: {
      [ROOT]: {
        id: ROOT,
        type: 'frame',
        name: 'Root',
        x: 0,
        y: 0,
        width: 800,
        height: 600,
        selrect: rootSel,
        points: [
          { x: 0, y: 0 },
          { x: 800, y: 0 },
          { x: 800, y: 600 },
          { x: 0, y: 600 },
        ],
        shapes: [RECT_A, RECT_B],
      },
      [RECT_A]: {
        id: RECT_A,
        type: 'rect',
        parentId: ROOT,
        frameId: ROOT,
        x: 0,
        y: 0,
        width: 100,
        height: 50,
        selrect: rSel,
        points: rPts,
        transform: t,
      },
      [RECT_B]: {
        id: RECT_B,
        type: 'rect',
        parentId: ROOT,
        frameId: ROOT,
        x: 200,
        y: 0,
        width: 100,
        height: 50,
        selrect: { ...rSel, x: 200, x1: 200, x2: 300 },
        points: rPts.map((p) => ({ x: p.x + 200, y: p.y })),
        transform: t,
      },
    },
  }
}

describe('changes-builder', () => {
  it('redo then undo restores geometry via processChanges', () => {
    const base = makePage()
    let page = structuredClone(base)
    const nodeBefore = page.objects[RECT_A]
    let builder = emptyChangesBuilder({ pageId: 'page1' })
    builder = appendModObjPair(builder, 'page1', RECT_A, {
      redoAssign: { x: 42, y: 7 },
      undoAssign: snapshotGeometryForUndo(nodeBefore),
    })
    const { redoChanges, undoChanges } = toCommitBundle(builder)
    page = processChanges(page, redoChanges)
    expect(page.objects[RECT_A].x).toBe(42)
    expect(page.objects[RECT_A].y).toBe(7)
    page = processChanges(page, undoChanges)
    expect(page.objects[RECT_A].x).toBe(0)
    expect(page.objects[RECT_A].y).toBe(0)
  })

  it('mergeBundle applies undos in reverse batch order', () => {
    let ba = emptyChangesBuilder({ pageId: 'page1' })
    const base = makePage()
    ba = appendModObjPair(ba, 'page1', RECT_A, {
      redoAssign: { x: 10 },
      undoAssign: snapshotGeometryForUndo(base.objects[RECT_A]),
    })
    let bb = emptyChangesBuilder({ pageId: 'page1' })
    bb = appendModObjPair(bb, 'page1', RECT_B, {
      redoAssign: { x: 999 },
      undoAssign: snapshotGeometryForUndo(base.objects[RECT_B]),
    })
    const merged = mergeBundle(ba, bb)
    let page = structuredClone(base)
    page = processChanges(page, merged.redoChanges)
    expect(page.objects[RECT_A].x).toBe(10)
    expect(page.objects[RECT_B].x).toBe(999)
    page = processChanges(page, merged.undoChanges)
    expect(page.objects[RECT_B].x).toBe(200)
    expect(page.objects[RECT_A].x).toBe(0)
  })
})
