/**
 * CRUD operations for document and pages.
 * Delegates to DocumentModel; updates are pushed by the model to workspace store and dev store.
 */

import { useWorkspaceStore } from './renderer/store/workspace-store'
import { DocumentModel } from './renderer/store/document-model'
import type { IndexedPage } from './worker/types'
import { flattenPageToIndexed } from './worker/types'
import type { PenpotDocument, PenpotNode, PenpotPage, Change } from 'penpot-exporter/types'

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
  const model = new DocumentModel()
  await model.loadDocument(document)
}

export async function setActivePage(pageId: string): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.setActivePage(pageId)
}

export async function addPage(page: IndexedPage | PenpotPage): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (!model) return
  const indexed: IndexedPage =
    'objects' in page && page.objects ? (page as IndexedPage) : flattenPageToIndexed(page as PenpotPage)
  await model.addPage(indexed)
}

export async function deletePage(pageId: string): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.deletePage(pageId)
}

export async function applyChanges(
  changes: Change[],
  options?: { pageId?: string }
): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.applyChanges(changes, options)
}
