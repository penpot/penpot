/**
 * DocumentModel holds document meta, pageMap, and current page id.
 * All node/page data lives in pageMap (IndexedPage); lookups use pageMap.get(pageId)?.objects[nodeId].
 * Pushes selectedNodes to workspace store and currentPageNodes/currentPageNodesMap to dev store.
 * Implements IDocumentModel for use by the workspace store.
 */

import type { PenpotDocument } from 'penpot-exporter/types'
import type { Change } from 'penpot-exporter/types'
import type { IndexedPage, IndexedNode } from '../../worker/types'
import { flattenPageToIndexed, unflattenIndexedPageToPage } from '../../worker/types'
import { processChanges } from '../../worker/process-changes'
import { useWorkspaceStore, type IDocumentModel } from './workspace-store'
import { useWorkspaceDevStore } from './workspace-dev-store'
import { commitPageUpdateWithChanges } from './commit'
import { enrichPageWithPositionData } from './enrich-position-data'
type DocumentMeta = Omit<PenpotDocument, 'children'>

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

/** Ordered list of node ids for current page (root first, then depth-first). Used for dev store. */
function orderedNodeIdsFromPage(page: IndexedPage): string[] {
  const ids: string[] = []
  const root = Object.values(page.objects).find((o) => o.parentId == null)
  if (!root) return ids
  ids.push(root.id)
  function walk(shapeIds: string[] | undefined) {
    if (!shapeIds?.length) return
    for (const id of shapeIds) {
      const obj = page.objects[id]
      if (obj) {
        ids.push(id)
        walk(obj.shapes)
      }
    }
  }
  walk(root.shapes)
  return ids
}

export class DocumentModel implements IDocumentModel {
  private documentMeta: DocumentMeta | null = null
  private pageMap: Map<string, IndexedPage> = new Map()
  private currentPageId: string | null = null

  getDocument(): PenpotDocument | null {
    if (!this.documentMeta) return null
    return {
      ...this.documentMeta,
      children: Array.from(this.pageMap.values()).map(unflattenIndexedPageToPage),
    }
  }

  getPage(id: string): IndexedPage | undefined {
    return this.pageMap.get(id)
  }

  /**
   * Internal: used only by commit to write updated page into the model.
   * Not on IDocumentModel.
   */
  applyPageUpdate(pageId: string, updatedPage: IndexedPage): void {
    this.pageMap.set(pageId, updatedPage)
    if (this.currentPageId === pageId) {
      this.pushToStores()
    }
  }

  getNode(id: string): IndexedNode | undefined {
    return this.pageMap.get(this.currentPageId ?? '')?.objects[id]
  }

  getSelectedNodes(selectedIds: Set<string>): IndexedNode[] {
    const objects = this.pageMap.get(this.currentPageId ?? '')?.objects
    if (!objects) return []
    const result: IndexedNode[] = []
    for (const id of selectedIds) {
      const node = objects[id]
      if (node) result.push(node)
    }
    return result
  }

  private pushToStores(): void {
    const workspace = useWorkspaceStore.getState()
    const dev = useWorkspaceDevStore.getState()
    const currentPage = this.currentPageId ? this.pageMap.get(this.currentPageId) : undefined
    if (currentPage) {
      const orderedIds = orderedNodeIdsFromPage(currentPage)
      const currentPageNodes = orderedIds.map((id) => currentPage.objects[id]).filter(Boolean)
      const currentPageNodesMap = currentPage.objects
      dev.setCurrentPageData({
        currentPageNodes,
        currentPageNodesMap,
      })
    } else {
      dev.setCurrentPageData({ currentPageNodes: [], currentPageNodesMap: {} })
    }
    const selectedNodes = this.getSelectedNodes(workspace.selectedIds)
    workspace.setSelectedNodes(selectedNodes)
  }

  async loadDocument(doc: PenpotDocument): Promise<void> {
    const { children, ...meta } = doc
    this.documentMeta = meta as DocumentMeta
    this.pageMap = buildPageMap(children)
    const firstPageId =
      children?.[0]?.id ?? (this.pageMap.size ? this.pageMap.keys().next().value ?? null : null)
    this.currentPageId = firstPageId

    useWorkspaceStore.setState({ documentModel: this, pageId: firstPageId })
    this.pushToStores()

    const state = useWorkspaceStore.getState()

    for (const page of this.pageMap.values()) {
      await state.workerClient?.addPage(page)
    }
    if (firstPageId && state.renderer) {
      const page = this.pageMap.get(firstPageId)
      if (page) {
        await state.renderer.initPage(page)
        state.updateViewport({ panX: 0, panY: 0, zoom: 1 })
        if (state.wasmModule && state.workerClient) {
          const penpotPage = unflattenIndexedPageToPage(page)
          const enrichedPenpot = enrichPageWithPositionData(state.wasmModule, penpotPage)
          const enrichedIndexed = flattenPageToIndexed(enrichedPenpot)
          this.pageMap.set(firstPageId, enrichedIndexed)
          await state.workerClient.updatePage(firstPageId, enrichedIndexed)
        }
      }
    }
  }

  async setActivePage(pageId: string): Promise<void> {
    const page = this.pageMap.get(pageId)
    if (!page) return
    const state = useWorkspaceStore.getState()
    if (!state.workerClient || !state.renderer) return

    this.currentPageId = pageId
    state.setPageId(pageId)
    this.pushToStores()

    await state.renderer.initPage(page)
    state.updateViewport({ panX: 0, panY: 0, zoom: 1 })
    if (state.wasmModule && state.workerClient) {
      const penpotPage = unflattenIndexedPageToPage(page)
      const enrichedPenpot = enrichPageWithPositionData(state.wasmModule, penpotPage)
      const enrichedIndexed = flattenPageToIndexed(enrichedPenpot)
      this.pageMap.set(pageId, enrichedIndexed)
      await state.workerClient.updatePage(pageId, enrichedIndexed)
    }
  }

  async addPage(page: IndexedPage): Promise<void> {
    if (!this.documentMeta) return
    const state = useWorkspaceStore.getState()
    const key = page.id ?? crypto.randomUUID()
    const pageWithId = { ...page, id: page.id ?? key }
    this.pageMap.set(key, pageWithId)

    if (state.workerClient) await state.workerClient.addPage(pageWithId)
    if (state.pageId == null && state.renderer?.isInitialized()) {
      await this.setActivePage(key)
    }
  }

  async deletePage(pageId: string): Promise<void> {
    if (!this.documentMeta) return
    const state = useWorkspaceStore.getState()
    this.pageMap.delete(pageId)
    const nextPageId =
      this.currentPageId === pageId
        ? this.pageMap.keys().next().value ?? null
        : this.currentPageId

    if (this.currentPageId === pageId && nextPageId) {
      this.currentPageId = nextPageId
      const page = this.pageMap.get(nextPageId)
      state.setPageId(nextPageId)
      this.pushToStores()
      if (state.renderer?.isInitialized() && page) {
        await state.renderer.initPage(page)
        state.updateViewport({ panX: 0, panY: 0, zoom: 1 })
        if (state.wasmModule && state.workerClient) {
          const penpotPage = unflattenIndexedPageToPage(page)
          const enrichedPenpot = enrichPageWithPositionData(state.wasmModule, penpotPage)
          const enrichedIndexed = flattenPageToIndexed(enrichedPenpot)
          this.pageMap.set(nextPageId, enrichedIndexed)
          await state.workerClient.updatePage(nextPageId, enrichedIndexed)
        }
      }
    } else {
      state.setPageId(nextPageId)
    }
  }

  async applyChanges(changes: Change[], options?: { pageId?: string }): Promise<void> {
    if (changes.length === 0) return
    const state = useWorkspaceStore.getState()
    const pageId = options?.pageId ?? changes[0]?.pageId ?? state.pageId
    const page = pageId ? this.pageMap.get(pageId) : undefined
    if (!pageId || !page) return
    const updatedPage = processChanges(page, changes)
    await commitPageUpdateWithChanges({
      pageId,
      updatedPage,
      changes,
    })
  }
}
