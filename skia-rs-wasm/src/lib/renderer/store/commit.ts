/**
 * Single commit pipeline: every page update goes through here so the worker (quadtree) and renderer stay in sync.
 */

import type { PenpotNode, PenpotPage } from 'penpot-exporter/lib'
import type { Change } from '@skia-rs-wasm/common'
import { useWorkspaceStore } from './workspace-store'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

function getRootFrameChildIds(page: PenpotPage): string[] {
  const ch = page.children ?? []
  if (ch.length <= 1) return []
  return ch
    .slice(1)
    .map((n: PenpotNode) => n.id)
    .filter((id: string | undefined): id is string => id != null)
}

interface RendererLike {
  isInitialized(): boolean
  addShape(node: PenpotNode): Promise<void>
  updateShape(node: PenpotNode): Promise<void>
  updateParentChildren(parentId: string, childIds: string[]): void
}

async function syncRendererAfterUpdate(
  renderer: RendererLike,
  oldPage: PenpotPage | undefined,
  updatedPage: PenpotPage
): Promise<void> {
  if (!renderer.isInitialized()) return
  const oldChildIds = new Set(
    (oldPage?.children ?? []).map((n: PenpotNode) => n.id).filter(Boolean)
  )
  const newChildren = updatedPage.children ?? []
  const newChildIds = newChildren
    .map((n: PenpotNode) => n.id)
    .filter((id: string | undefined): id is string => id != null)
  const newIdsSet = new Set(newChildIds)
  const added = newChildren.filter((n: PenpotNode) => n.id && !oldChildIds.has(n.id))
  const deleted = (oldPage?.children ?? []).filter(
    (n: PenpotNode) => n.id && !newIdsSet.has(n.id)
  )
  const sameIds =
    newChildIds.length === (oldPage?.children ?? []).length &&
    added.length === 0 &&
    deleted.length === 0
  const rootId = newChildren[0]?.id ?? ROOT_UUID
  const childIds = getRootFrameChildIds(updatedPage)
  if (added.length > 0) {
    for (const node of added) {
      if (node.id) await renderer.addShape(node)
    }
    renderer.updateParentChildren(rootId, childIds)
  } else if (deleted.length > 0) {
    renderer.updateParentChildren(rootId, childIds)
  } else if (!sameIds) {
    for (const node of newChildren) {
      if (node.id) await renderer.updateShape(node)
    }
  } else {
    const changed = newChildren.filter((n: PenpotNode) => {
      const oldNode = (oldPage?.children ?? []).find((o: PenpotNode) => o.id === n.id)
      return oldNode && JSON.stringify(oldNode) !== JSON.stringify(n)
    })
    for (const node of changed) {
      if (node.id) await renderer.updateShape(node)
    }
  }
}

export interface PageCommitPayload {
  pageId: string
  updatedPage: PenpotPage
}

export interface PageCommitWithChangesPayload {
  pageId: string
  updatedPage: PenpotPage
  changes: Change[]
}

export async function commitPageUpdate(payload: PageCommitPayload): Promise<void> {
  const { pageId, updatedPage } = payload
  const state = useWorkspaceStore.getState()
  const { documentModel, workerClient, renderer } = state
  if (!documentModel) return
  const oldPage = documentModel.getPage(pageId)
  documentModel.setPage(pageId, updatedPage)
  if (workerClient) await workerClient.updatePage(pageId, updatedPage)
  if (renderer) await syncRendererAfterUpdate(renderer, oldPage, updatedPage)
}

export async function commitPageUpdateWithChanges(payload: PageCommitWithChangesPayload): Promise<void> {
  const { pageId, updatedPage, changes } = payload
  const state = useWorkspaceStore.getState()
  const { documentModel, workerClient, renderer } = state
  if (!documentModel) return
  const oldPage = documentModel.getPage(pageId)
  documentModel.setPage(pageId, updatedPage)
  if (workerClient && changes.length > 0) {
    await workerClient.updatePageWithChanges(pageId, changes)
  } else if (workerClient) {
    await workerClient.updatePage(pageId, updatedPage)
  }
  if (renderer) await syncRendererAfterUpdate(renderer, oldPage, updatedPage)
}
