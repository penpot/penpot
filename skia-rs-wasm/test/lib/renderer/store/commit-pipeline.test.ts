import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Change } from 'penpot-exporter/types'
import type { IndexedPage } from '../../../../src/lib/worker/types'
import type { DocumentModel } from '../../../../src/lib/renderer/store/document-model'
import { useWorkspaceStore } from '../../../../src/lib/renderer/store/workspace-store'
import { useHistoryStore } from '../../../../src/lib/history/history-store'
import { commitChanges } from '../../../../src/lib/renderer/store/commit'

const PAGE_ID = 'page1'
const ROOT = '00000000-0000-0000-0000-000000000000'
const RECT = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

function makePage(): IndexedPage {
  const rootSel = { x: 0, y: 0, width: 800, height: 600, x1: 0, y1: 0, x2: 800, y2: 600 }
  const rSel = { x: 0, y: 0, width: 100, height: 50, x1: 0, y1: 0, x2: 100, y2: 50 }
  const t = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 }
  return {
    id: PAGE_ID,
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
        shapes: [RECT],
      },
      [RECT]: {
        id: RECT,
        type: 'rect',
        parentId: ROOT,
        frameId: ROOT,
        x: 0,
        y: 0,
        width: 100,
        height: 50,
        selrect: rSel,
        points: [
          { x: 0, y: 0 },
          { x: 100, y: 0 },
          { x: 100, y: 50 },
          { x: 0, y: 50 },
        ],
        transform: t,
      },
    },
  }
}

describe('commitChanges pipeline', () => {
  let page: IndexedPage
  const order: string[] = []

  beforeEach(() => {
    order.length = 0
    page = structuredClone(makePage())
    useHistoryStore.setState({ undoStack: [], redoStack: [] })

    const mockModel = {
      getPage: (id: string) => (id === PAGE_ID ? page : undefined),
      getActiveOrSinglePageId: () => PAGE_ID,
      applyPageUpdate: (_id: string, updated: IndexedPage) => {
        order.push('local')
        page = updated
      },
    } as unknown as DocumentModel

    const workerClient = {
      updatePageWithChanges: vi.fn(async () => {
        order.push('worker')
      }),
      updatePage: vi.fn(async () => {
        order.push('worker-full')
      }),
    }

    useWorkspaceStore.setState({
      documentModel: mockModel,
      workerClient: workerClient as never,
      renderer: null,
      pageId: PAGE_ID,
    })
  })

  it('runs local apply before worker update', async () => {
    const redo: Change[] = [
      {
        type: 'mod-obj',
        id: RECT,
        operations: [{ type: 'assign', value: { x: 5 } }],
      },
    ]
    await commitChanges({ redoChanges: redo, pageId: PAGE_ID, saveUndo: false })
    expect(order).toEqual(['local', 'worker'])
    expect(page.objects[RECT].x).toBe(5)
  })

  it('does not push history when fromHistory is true', async () => {
    const redo: Change[] = [
      {
        type: 'mod-obj',
        id: RECT,
        operations: [{ type: 'assign', value: { x: 1 } }],
      },
    ]
    const undo: Change[] = [
      {
        type: 'mod-obj',
        id: RECT,
        operations: [{ type: 'assign', value: { x: 0 } }],
      },
    ]
    await commitChanges({
      redoChanges: redo,
      undoChanges: undo,
      pageId: PAGE_ID,
      fromHistory: true,
    })
    expect(useHistoryStore.getState().undoStack).toHaveLength(0)
  })

  it('pushes undo frame when undoChanges provided', async () => {
    const redo: Change[] = [
      {
        type: 'mod-obj',
        id: RECT,
        operations: [{ type: 'assign', value: { x: 3 } }],
      },
    ]
    const undo: Change[] = [
      {
        type: 'mod-obj',
        id: RECT,
        operations: [{ type: 'assign', value: { x: 0 } }],
      },
    ]
    await commitChanges({ redoChanges: redo, undoChanges: undo, pageId: PAGE_ID })
    expect(useHistoryStore.getState().undoStack).toHaveLength(1)
    expect(useHistoryStore.getState().undoStack[0].redoChanges).toEqual(redo)
  })

  it('applies commits when workspace pageId is null but document exposes active page', async () => {
    useWorkspaceStore.setState({ pageId: null })
    const redo: Change[] = [
      {
        type: 'mod-obj',
        id: RECT,
        operations: [{ type: 'assign', value: { x: 9 } }],
      },
    ]
    await commitChanges({ redoChanges: redo, saveUndo: false })
    expect(order).toEqual(['local', 'worker'])
    expect(page.objects[RECT].x).toBe(9)
  })
})
