/**
 * DocumentModel orchestrates document/page lifecycle and sync with worker/renderer.
 * Persistent document state lives in docProxy (Valtio).
 */

import type { PenpotDocument } from 'penpot-exporter/types'
import type { Change } from 'penpot-exporter/types'
import type { IndexedPage, IndexedNode } from '../../worker/types'
import { flattenPageToIndexed, unflattenIndexedPageToPage } from '../../worker/types'
import { useWorkspaceStore } from './workspace-store'
import { viewport } from '../signals/pointer'
import { commitChanges } from './commit'
import { useHistoryStore } from '../../history/history-store'
import { enrichPageWithPositionData } from './enrich-position-data'
import { setSelectedIds } from './document-selection'
import { docProxy, getActiveOrSinglePageId, type DocumentMeta } from './doc-proxy'

function buildPageMap(children: PenpotDocument['children']): Map<string, IndexedPage> {
  const map = new Map<string, IndexedPage>()
  if (!children?.length) return map
  for (const page of children) {
    const key = page.id ?? crypto.randomUUID()
    const indexed = flattenPageToIndexed({ ...page, id: page.id ?? key })
    map.set(key, indexed)
  }
  return map
}

export class DocumentModel {

  getDocument(): PenpotDocument | null {
    if (!docProxy.meta) return null
    return {
      ...docProxy.meta,
      children: Array.from(docProxy.pageMap.values()).map(unflattenIndexedPageToPage),
    }
  }

  getPage(id: string): IndexedPage | undefined {
    return docProxy.pageMap.get(id)
  }

  getActiveOrSinglePageId(): string | null {
    return getActiveOrSinglePageId()
  }

  /**
   * Internal: used only by commit to write updated page into the model.
   * Not on IDocumentModel.
   */
  applyPageUpdate(pageId: string, updatedPage: IndexedPage): void {
    docProxy.pageMap.set(pageId, updatedPage)
  }

  getNode(id: string): IndexedNode | undefined {
    return docProxy.pageMap.get(docProxy.currentPageId ?? '')?.objects[id]
  }

  getSelectedNodes(selectedIds: Set<string>): IndexedNode[] {
    const objects = docProxy.pageMap.get(docProxy.currentPageId ?? '')?.objects
    if (!objects) return []
    const result: IndexedNode[] = []
    for (const id of selectedIds) {
      const node = objects[id]
      if (node) result.push(node)
    }
    return result
  }

  async loadDocument(doc: PenpotDocument): Promise<void> {
    useHistoryStore.getState().clearHistory()
    const { children, ...meta } = doc
    docProxy.meta = meta as DocumentMeta
    const pageMap = buildPageMap(children)
    docProxy.pageMap.clear()
    for (const [pageId, page] of pageMap) {
      docProxy.pageMap.set(pageId, page)
    }
    const firstPageId =
      children?.[0]?.id ?? (docProxy.pageMap.size ? docProxy.pageMap.keys().next().value ?? null : null)
    docProxy.currentPageId = firstPageId

    const state = useWorkspaceStore.getState()
    setSelectedIds(new Set())

    for (const page of docProxy.pageMap.values()) {
      await state.workerClient?.addPage(page)
    }
    if (firstPageId && state.renderer) {
      const page = docProxy.pageMap.get(firstPageId)
      if (page) {
        await state.renderer.initPage(page)
        viewport.value = { panX: 0, panY: 0, zoom: 1 }
        if (state.wasmModule && state.workerClient) {
          const penpotPage = unflattenIndexedPageToPage(page)
          const enrichedPenpot = enrichPageWithPositionData(state.wasmModule, penpotPage)
          const enrichedIndexed = flattenPageToIndexed(enrichedPenpot)
          docProxy.pageMap.set(firstPageId, enrichedIndexed)
          await state.workerClient.updatePage(firstPageId, enrichedIndexed)
        }
      }
    }
  }

  async setActivePage(pageId: string): Promise<void> {
    useHistoryStore.getState().clearHistory()
    const page = docProxy.pageMap.get(pageId)
    if (!page) return
    const state = useWorkspaceStore.getState()
    if (!state.workerClient || !state.renderer) return

    docProxy.currentPageId = pageId
    setSelectedIds(new Set())

    await state.renderer.initPage(page)
    viewport.value = { panX: 0, panY: 0, zoom: 1 }
    if (state.wasmModule && state.workerClient) {
      const penpotPage = unflattenIndexedPageToPage(page)
      const enrichedPenpot = enrichPageWithPositionData(state.wasmModule, penpotPage)
      const enrichedIndexed = flattenPageToIndexed(enrichedPenpot)
      docProxy.pageMap.set(pageId, enrichedIndexed)
      await state.workerClient.updatePage(pageId, enrichedIndexed)
    }
  }

  async addPage(page: IndexedPage): Promise<void> {
    if (!docProxy.meta) return
    const state = useWorkspaceStore.getState()
    const key = page.id ?? crypto.randomUUID()
    const pageWithId = { ...page, id: page.id ?? key }
    docProxy.pageMap.set(key, pageWithId)

    if (state.workerClient) await state.workerClient.addPage(pageWithId)
    if (getActiveOrSinglePageId() == null && state.renderer?.isInitialized()) {
      await this.setActivePage(key)
    }
  }

  async deletePage(pageId: string): Promise<void> {
    if (!docProxy.meta) return
    useHistoryStore.getState().clearHistory()
    const state = useWorkspaceStore.getState()
    docProxy.pageMap.delete(pageId)
    const nextPageId =
      docProxy.currentPageId === pageId
        ? docProxy.pageMap.keys().next().value ?? null
        : docProxy.currentPageId

    if (docProxy.currentPageId === pageId && nextPageId) {
      docProxy.currentPageId = nextPageId
      const page = docProxy.pageMap.get(nextPageId)
      setSelectedIds(new Set())
      if (state.renderer?.isInitialized() && page) {
        await state.renderer.initPage(page)
        viewport.value = { panX: 0, panY: 0, zoom: 1 }
        if (state.wasmModule && state.workerClient) {
          const penpotPage = unflattenIndexedPageToPage(page)
          const enrichedPenpot = enrichPageWithPositionData(state.wasmModule, penpotPage)
          const enrichedIndexed = flattenPageToIndexed(enrichedPenpot)
          docProxy.pageMap.set(nextPageId, enrichedIndexed)
          await state.workerClient.updatePage(nextPageId, enrichedIndexed)
        }
      }
    } else {
      docProxy.currentPageId = nextPageId
    }
  }

  async applyChanges(
    changes: Change[],
    options?: { pageId?: string; undoChanges?: Change[] }
  ): Promise<void> {
    if (changes.length === 0) return
    const pageId =
      options?.pageId ??
      (changes[0] as { pageId?: string }).pageId ??
      this.getActiveOrSinglePageId()
    if (!pageId || !docProxy.pageMap.get(pageId)) return
    await commitChanges({
      redoChanges: changes,
      undoChanges: options?.undoChanges ?? [],
      pageId,
    })
  }
}

export const documentModel = new DocumentModel()
