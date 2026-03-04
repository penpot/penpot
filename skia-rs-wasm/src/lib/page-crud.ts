/**
 * CRUD operations for document and pages.
 * Delegates to DocumentModel; updates are pushed by the model to workspace store and dev store.
 */

import { useWorkspaceStore } from './renderer/store/workspace-store'
import { DocumentModel } from './renderer/store/document-model'
import { commitPageUpdate } from './renderer/store/commit'
import { propagateModifiers } from './renderer/api/modifiers'
import { applyTransformToNode } from './renderer/geom/apply-transform-to-node'
import type { PenpotDocument, PenpotNode, PenpotPage } from 'penpot-exporter/lib'
import type { Matrix } from 'penpot-exporter/lib'

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

export async function addPage(page: PenpotPage): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.addPage(page)
}

export async function updatePage(page: PenpotPage & { pageId: string }): Promise<void> {
  const { pageId, ...pageData } = page
  const updatedPage = { ...pageData, id: pageId } as PenpotPage
  await commitPageUpdate({ pageId, updatedPage })
}

export async function deletePage(pageId: string): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.deletePage(pageId)
}

export async function addNode(node: PenpotNode): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.addNode(node)
}

export async function updateNode(nodeId: string, updates: Partial<PenpotNode>): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.updateNode(nodeId, updates)
}

export async function applyNodeUpdates(updates: Record<string, Partial<PenpotNode>>): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.applyNodeUpdates(updates)
}

export async function applyModifiersAndCommit(
  entries: Array<[string, Matrix]>,
  options?: { pixelPrecision?: number }
): Promise<void> {
  const state = useWorkspaceStore.getState()
  const { renderer, documentModel } = state
  const module = renderer?.getModule?.()
  if (!module || !documentModel) return
  const result = propagateModifiers(module, entries, options?.pixelPrecision ?? 0)
  const updates: Record<string, Partial<PenpotNode>> = {}
  for (const { id, transform } of result) {
    const node = documentModel.getNode(id)
    if (node) {
      const partial = applyTransformToNode(node, transform)
      if (partial) updates[id] = partial
    }
  }
  if (Object.keys(updates).length > 0) await documentModel.applyNodeUpdates(updates)
}

export async function deleteNode(nodeId: string): Promise<void> {
  const model = useWorkspaceStore.getState().documentModel
  if (model) await model.deleteNode(nodeId)
}
