/**
 * Penpot-shaped commit pipeline: apply changes locally (document + renderer), then worker indexes.
 * History frames are pushed when undo inverse is supplied (unless fromHistory / saveUndo false).
 */

import type { IndexedPage, IndexedNode } from '../../worker/types'
import type { Change } from 'penpot-exporter/types'
import { processChanges } from '../../worker/process-changes'
import { useWorkspaceStore } from './workspace-store'
import type { CommitChangesParams } from '../../changes/commit-types'
import { useHistoryStore } from '../../history/history-store'
import type { WorkerClient } from '../../worker/types'
import { assertValidAddObjChange } from '../../common/shape-id'
import { docProxy, getActiveOrSinglePageId } from './doc-proxy'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

function toPlainPage(page: IndexedPage): IndexedPage {
  try {
    return structuredClone(page)
  } catch {
    return JSON.parse(JSON.stringify(page)) as IndexedPage
  }
}

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
    const subParentsToUpdate = new Set<string>()
    for (const id of added) {
      const node = newObjects[id]
      if (node?.parentId && node.parentId !== rootId) {
        subParentsToUpdate.add(node.parentId)
      }
    }
    for (const parentId of subParentsToUpdate) {
      const parentNode = newObjects[parentId]
      const parentShapes = (parentNode as { shapes?: string[] })?.shapes
      if (parentShapes) {
        renderer.updateParentChildren(parentId, parentShapes)
      }
    }
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

function changePageId(c: Change): string | undefined {
  return (c as { pageId?: string }).pageId
}

/** Group changes by `pageId`, using fallback when absent on a change. */
export function groupChangesByPageId(
  changes: Change[],
  fallbackPageId: string | null | undefined
): Map<string, Change[]> {
  const map = new Map<string, Change[]>()
  for (const c of changes) {
    const pid = changePageId(c) ?? fallbackPageId
    if (!pid) continue
    const list = map.get(pid) ?? []
    list.push(c)
    map.set(pid, list)
  }
  return map
}

export interface ApplyChangesLocallyParams {
  pageId: string
  redoChanges: Change[]
  ignoreRendererSync?: boolean
}

/**
 * Apply redo changes to the document model and optionally sync the renderer. Does not touch the worker.
 */
export async function applyChangesLocally(params: ApplyChangesLocallyParams): Promise<IndexedPage | undefined> {
  const { pageId, redoChanges, ignoreRendererSync } = params
  const state = useWorkspaceStore.getState()
  const { renderer } = state
  if (redoChanges.length === 0) return undefined

  const page = docProxy.pageMap.get(pageId)
  if (!page) return undefined

  const oldPage = toPlainPage(page)
  /** Clone so `processChanges` does not mutate live page shapes; stale renderer/worker diffs used ref/JSON on shared objects. */
  const updatedPage = processChanges(toPlainPage(page), redoChanges)
  docProxy.pageMap.set(pageId, updatedPage)

  if (renderer && !ignoreRendererSync) {
    await syncRendererAfterUpdate(renderer, oldPage, updatedPage)
  }
  return updatedPage
}

/**
 * Update worker spatial index for one page (incremental changes when non-empty).
 */
export async function updateWorkerIndexes(
  workerClient: WorkerClient | null,
  pageId: string,
  changes: Change[],
  updatedPage: IndexedPage
): Promise<void> {
  if (!workerClient) return
  if (changes.length > 0) {
    await workerClient.updatePageWithChanges(pageId, changes)
  } else {
    await workerClient.updatePage(pageId, updatedPage)
  }
}

/**
 * Orchestrate local apply, worker indexes, and optional history push.
 */
export async function commitChanges(params: CommitChangesParams): Promise<void> {
  const {
    redoChanges,
    undoChanges = [],
    pageId: explicitPageId,
    saveUndo,
    fromHistory,
    ignoreRendererSync,
  } = params

  if (redoChanges.length === 0) return

  for (const c of redoChanges) {
    if (c.type === 'add-obj') {
      assertValidAddObjChange(c)
    }
  }

  const state = useWorkspaceStore.getState()
  const { workerClient } = state

  const fallbackPageId = explicitPageId ?? getActiveOrSinglePageId()
  const byPage = groupChangesByPageId(redoChanges, fallbackPageId)
  if (byPage.size === 0) return

  for (const [pageId, pageChanges] of byPage) {
    await applyChangesLocally({
      pageId,
      redoChanges: pageChanges,
      ignoreRendererSync,
    })
  }

  // Fire worker index update in background — don't block visual commit.
  // cleanModifiers() fires as soon as commitChanges returns; the worker
  // spatial-index update is only needed for hit-testing and can lag behind.
  for (const [pageId, pageChanges] of byPage) {
    const updatedPage = docProxy.pageMap.get(pageId)
    if (!updatedPage) continue
    updateWorkerIndexes(workerClient, pageId, pageChanges, updatedPage).catch(console.error)
  }

  const effectiveSaveUndo = saveUndo ?? undoChanges.length > 0
  if (!fromHistory && effectiveSaveUndo && undoChanges.length > 0) {
    useHistoryStore.getState().pushCommitFrame({
      redoChanges,
      undoChanges,
    })
  }
}

export interface PageCommitPayload {
  pageId: string
  updatedPage: IndexedPage
}

export interface PageCommitWithChangesPayload {
  pageId: string
  changes: Change[]
}

export async function commitPageUpdate(payload: PageCommitPayload): Promise<void> {
  const { pageId, updatedPage } = payload
  const state = useWorkspaceStore.getState()
  const { workerClient, renderer } = state
  const oldPage = docProxy.pageMap.get(pageId)
  const plainUpdatedPage = toPlainPage(updatedPage)
  docProxy.pageMap.set(pageId, plainUpdatedPage)
  if (workerClient) await workerClient.updatePage(pageId, plainUpdatedPage)
  if (renderer) await syncRendererAfterUpdate(renderer, oldPage ? toPlainPage(oldPage) : undefined, plainUpdatedPage)
}

export async function commitPageUpdateWithChanges(payload: PageCommitWithChangesPayload): Promise<void> {
  await commitChanges({
    redoChanges: payload.changes,
    undoChanges: [],
    pageId: payload.pageId,
    saveUndo: false,
  })
}
