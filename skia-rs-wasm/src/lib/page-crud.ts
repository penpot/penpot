/**
 * CRUD operations for document and pages.
 * Delegates to DocumentModel orchestration backed by Valtio document state.
 */

import { documentModel } from './renderer/store/document-model'
import { commitChanges } from './renderer/store/commit'
import { useHistoryStore } from './history/history-store'
import type { IndexedPage } from './worker/types'
import { flattenPageToIndexed } from './worker/types'
import type { PenpotDocument, PenpotNode, PenpotPage, Change } from 'penpot-exporter/types'
import type { CommitChangesParams } from './changes/commit-types'

export function createNewDocument(): PenpotDocument {
  const ROOT_UUID = '00000000-0000-0000-0000-000000000000'
  const rootFrame: PenpotNode = {
    id: ROOT_UUID,
    name: 'Root',
    type: 'frame',
    x: 0,
    y: 0,
    width: 800,
    height: 600,
    parentId: undefined,
    selrect: { x: 0, y: 0, width: 800, height: 600, x1: 0, y1: 0, x2: 800, y2: 600 },
    points: [{ x: 0, y: 0 }, { x: 800, y: 0 }, { x: 800, y: 600 }, { x: 0, y: 600 }],
  }
  const initialPage: PenpotPage = {
    id: crypto.randomUUID(),
    name: 'Page 1',
    children: [rootFrame],
    background: '#FFFFFF',
  }
  return {
    name: 'Untitled',
    children: [initialPage],
    components: {},
    images: {},
    paintStyles: {},
    textStyles: {},
    componentProperties: {},
    externalLibraries: {},
    missingFonts: [],
    isShared: false,
  }
}

export async function setDocument(document: PenpotDocument): Promise<void> {
  await documentModel.loadDocument(document)
}

export async function setActivePage(pageId: string): Promise<void> {
  await documentModel.setActivePage(pageId)
}

export async function addPage(page: IndexedPage | PenpotPage): Promise<void> {
  const indexed: IndexedPage =
    'objects' in page && page.objects ? (page as IndexedPage) : flattenPageToIndexed(page as PenpotPage)
  await documentModel.addPage(indexed)
}

export async function deletePage(pageId: string): Promise<void> {
  await documentModel.deletePage(pageId)
}

export async function applyChanges(
  changes: Change[],
  options?: { pageId?: string; undoChanges?: Change[] }
): Promise<void> {
  await documentModel.applyChanges(changes, options)
}

/** Full commit with optional undo vector (Penpot-shaped pipeline + history). */
export async function commitChangesPublic(params: CommitChangesParams): Promise<void> {
  await commitChanges(params)
}

export async function undo(): Promise<void> {
  const frame = useHistoryStore.getState().popUndoFrame()
  if (!frame) return
  await commitChanges({
    redoChanges: frame.undoChanges,
    saveUndo: false,
    fromHistory: true,
  })
  useHistoryStore.getState().pushRedoFrame(frame)
}

export async function redo(): Promise<void> {
  const frame = useHistoryStore.getState().popRedoFrame()
  if (!frame) return
  await commitChanges({
    redoChanges: frame.redoChanges,
    saveUndo: false,
    fromHistory: true,
  })
  useHistoryStore.getState().pushUndoFrame(frame)
}
