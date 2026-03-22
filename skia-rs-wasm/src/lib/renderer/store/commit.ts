/**
 * Penpot-shaped commit pipeline: apply changes locally (document + renderer), then worker indexes.
 * History frames are pushed when undo inverse is supplied (unless fromHistory / saveUndo false).
 */

import type { IndexedPage, IndexedNode } from '../../worker/types'
import type { Change } from 'penpot-exporter/types'
import { processChanges } from '../../worker/process-changes'
import { useWorkspaceStore } from './workspace-store'
import type { DocumentModel } from './document-model'
import type { CommitChangesParams } from '../../changes/commit-types'
import { useHistoryStore } from '../../history/history-store'
import type { WorkerClient } from '../../worker/types'

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
  const { documentModel, renderer } = state
  if (!documentModel || redoChanges.length === 0) return undefined

  const page = documentModel.getPage(pageId)
  if (!page) return undefined

  const oldPage = page
  /** Clone so `processChanges` does not mutate live page shapes; stale renderer/worker diffs used ref/JSON on shared objects. */
  const updatedPage = processChanges(structuredClone(page), redoChanges)
  ;(documentModel as DocumentModel).applyPageUpdate(pageId, updatedPage)

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

  const state = useWorkspaceStore.getState()
  const { documentModel, workerClient } = state
  if (!documentModel) return

  const fallbackPageId =
    explicitPageId ?? state.pageId ?? documentModel.getActiveOrSinglePageId()
  const byPage = groupChangesByPageId(redoChanges, fallbackPageId)
  if (byPage.size === 0) return

  for (const [pageId, pageChanges] of byPage) {
    await applyChangesLocally({
      pageId,
      redoChanges: pageChanges,
      ignoreRendererSync,
    })
  }

  for (const [pageId, pageChanges] of byPage) {
    const updatedPage = documentModel.getPage(pageId)
    if (!updatedPage) continue
    await updateWorkerIndexes(workerClient, pageId, pageChanges, updatedPage)
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
  const { documentModel, workerClient, renderer } = state
  if (!documentModel) return
  const oldPage = documentModel.getPage(pageId)
  ;(documentModel as DocumentModel).applyPageUpdate(pageId, updatedPage)
  if (workerClient) await workerClient.updatePage(pageId, updatedPage)
  if (renderer) await syncRendererAfterUpdate(renderer, oldPage, updatedPage)
}

export async function commitPageUpdateWithChanges(payload: PageCommitWithChangesPayload): Promise<void> {
  await commitChanges({
    redoChanges: payload.changes,
    undoChanges: [],
    pageId: payload.pageId,
    saveUndo: false,
  })
}
