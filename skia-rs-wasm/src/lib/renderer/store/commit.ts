/**
 * Single commit pipeline: every page update goes through here so the worker (quadtree) and renderer stay in sync.
 */

import type { IndexedPage, IndexedNode } from '../../worker/types'
import type { Change } from 'penpot-exporter/lib'
import { useWorkspaceStore } from './workspace-store'
import type { DocumentModel } from './document-model'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

function getRootFrameId(page: IndexedPage): string | undefined {
  const root = Object.values(page.objects).find((o) => o.parentId == null)
  return root?.id
}

function getRootFrameChildIds(page: IndexedPage): string[] {
  const root = Object.values(page.objects).find((o) => o.parentId == null)
  return root?.shapes ?? []
}

interface RendererLike {
  isInitialized(): boolean
  addShape(node: IndexedNode): Promise<void>
  updateShape(node: IndexedNode): Promise<void>
  updateParentChildren(parentId: string, childIds: string[]): void
}

async function syncRendererAfterUpdate(
  renderer: RendererLike,
  oldPage: IndexedPage | undefined,
  updatedPage: IndexedPage
): Promise<void> {
  if (!renderer.isInitialized()) return
  const oldObjects = oldPage?.objects ?? {}
  const newObjects = updatedPage.objects
  const oldIds = new Set(Object.keys(oldObjects))
  const newIds = new Set(Object.keys(newObjects))
  const added = [...newIds].filter((id) => !oldIds.has(id))
  const deleted = [...oldIds].filter((id) => !newIds.has(id))
  const rootId = getRootFrameId(updatedPage) ?? ROOT_UUID
  const childIds = getRootFrameChildIds(updatedPage)

  if (added.length > 0) {
    for (const id of added) {
      const node = newObjects[id]
      if (node) await renderer.addShape(node)
    }
    renderer.updateParentChildren(rootId, childIds)
  } else if (deleted.length > 0) {
    renderer.updateParentChildren(rootId, childIds)
  } else {
    const changed = [...newIds].filter((id) => {
      const oldNode = oldObjects[id]
      const newNode = newObjects[id]
      return oldNode && newNode && JSON.stringify(oldNode) !== JSON.stringify(newNode)
    })
    for (const id of changed) {
      const node = newObjects[id]
      if (node) await renderer.updateShape(node)
    }
  }
}

export interface PageCommitPayload {
  pageId: string
  updatedPage: IndexedPage
}

export interface PageCommitWithChangesPayload {
  pageId: string
  updatedPage: IndexedPage
  changes: Change[]
}

export async function commitPageUpdate(payload: PageCommitPayload): Promise<void> {
  const { pageId, updatedPage } = payload
  const state = useWorkspaceStore.getState()
  const { documentModel, workerClient, renderer } = state
  if (!documentModel) return
  const oldPage = documentModel.getPage(pageId)
  ;(documentModel as DocumentModel).applyPageUpdate(pageId, updatedPage)
  if (workerClient) await workerClient.updatePage(pageId, updatedPage)
  if (renderer) await syncRendererAfterUpdate(renderer, oldPage, updatedPage)
}

export async function commitPageUpdateWithChanges(payload: PageCommitWithChangesPayload): Promise<void> {
  const { pageId, updatedPage, changes } = payload
  const state = useWorkspaceStore.getState()
  const { documentModel, workerClient, renderer } = state
  if (!documentModel) return
  const oldPage = documentModel.getPage(pageId)
  ;(documentModel as DocumentModel).applyPageUpdate(pageId, updatedPage)
  if (workerClient && changes.length > 0) {
    await workerClient.updatePageWithChanges(pageId, changes)
  } else if (workerClient) {
    await workerClient.updatePage(pageId, updatedPage)
  }
  if (renderer) await syncRendererAfterUpdate(renderer, oldPage, updatedPage)
}
