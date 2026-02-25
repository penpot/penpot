/**
 * DocumentModel holds document meta, pageMap, and current page derived data.
 * Pushes selectedNodes to workspace store and currentPageNodes/currentPageNodesMap to dev store.
 * Implements IDocumentModel for use by the workspace store.
 */

import type { PenpotDocument, PenpotNode, PenpotPage } from '@penpot-exporter/types'
import type { AddObjChange, ModObjChange, DelObjChange } from '@skia-rs-wasm/common'
import { ZERO_UUID } from '@skia-rs-wasm/common'
import { useWorkspaceStore } from './workspace-store'
import { useWorkspaceDevStore } from './workspace-dev-store'
import { Viewport } from '../viewport'
import { commitPageUpdateWithChanges } from './commit'
import { enrichPageWithPositionData } from './enrich-position-data'

const EMPTY_NODES: PenpotNode[] = []
const EMPTY_MAP: Record<string, PenpotNode> = {}

type DocumentMeta = Omit<PenpotDocument, 'children'>
type CurrentPageNodes = NonNullable<PenpotPage['children']>

function buildPageMap(children: PenpotPage[] | undefined): Map<string, PenpotPage> {
  const map = new Map<string, PenpotPage>()
  if (!children?.length) return map
  for (const page of children) {
    const key = page.id ?? crypto.randomUUID()
    map.set(key, { ...page, id: page.id ?? key })
  }
  return map
}

function flattenPageNodes(page: PenpotPage | null | undefined): Record<string, PenpotNode> {
  const acc: Record<string, PenpotNode> = {}
  if (!page?.children?.length) return acc
  function walk(nodes: PenpotNode[]): void {
    for (const node of nodes) {
      acc[node.id] = node
      const childList = (node as { children?: PenpotNode[] }).children
      if (childList?.length) walk(childList)
    }
  }
  walk(page.children)
  return acc
}

export class DocumentModel {
  private documentMeta: DocumentMeta | null = null
  private pageMap: Map<string, PenpotPage> = new Map()
  private currentPageId: string | null = null
  private currentPageNodes: CurrentPageNodes = EMPTY_NODES
  private currentPageNodesMap: Record<string, PenpotNode> = EMPTY_MAP

  getDocument(): PenpotDocument | null {
    if (!this.documentMeta) return null
    return {
      ...this.documentMeta,
      children: Array.from(this.pageMap.values()),
    }
  }

  getPage(id: string): PenpotPage | undefined {
    return this.pageMap.get(id)
  }

  setPage(pageId: string, updatedPage: PenpotPage): void {
    this.pageMap.set(pageId, updatedPage)
    if (this.currentPageId === pageId) {
      this.currentPageNodes = updatedPage.children ?? EMPTY_NODES
      this.currentPageNodesMap = flattenPageNodes(updatedPage)
      this.pushToStores()
    }
  }

  getSelectedNodes(selectedIds: Set<string>): PenpotNode[] {
    const result: PenpotNode[] = []
    for (const id of selectedIds) {
      const node = this.currentPageNodesMap[id]
      if (node) result.push(node)
    }
    return result
  }

  private pushToStores(): void {
    const workspace = useWorkspaceStore.getState()
    const dev = useWorkspaceDevStore.getState()
    dev.setCurrentPageData({
      currentPageNodes: this.currentPageNodes,
      currentPageNodesMap: this.currentPageNodesMap,
    })
    const selectedNodes = this.getSelectedNodes(workspace.selectedIds)
    workspace.setSelectedNodes(selectedNodes)
  }

  async loadDocument(doc: PenpotDocument): Promise<void> {
    const { children, ...meta } = doc
    this.documentMeta = meta as DocumentMeta
    this.pageMap = buildPageMap(children)
    const firstPageId =
      children?.[0]?.id ?? (this.pageMap.size ? this.pageMap.keys().next().value ?? null : null)
    const firstPage = firstPageId ? this.pageMap.get(firstPageId) : undefined
    this.currentPageId = firstPageId
    this.currentPageNodes = firstPage?.children ?? EMPTY_NODES
    this.currentPageNodesMap = firstPage ? flattenPageNodes(firstPage) : EMPTY_MAP

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
        state.setViewport(new Viewport())
        if (state.wasmModule && state.workerClient) {
          const enrichedPage = enrichPageWithPositionData(state.wasmModule, page)
          await state.workerClient.updatePage(firstPageId, enrichedPage)
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
    this.currentPageNodes = page.children ?? EMPTY_NODES
    this.currentPageNodesMap = flattenPageNodes(page)

    state.setPageId(pageId)
    this.pushToStores()

    await state.renderer.initPage(page)
    state.setViewport(new Viewport())
    if (state.wasmModule && state.workerClient) {
      const enrichedPage = enrichPageWithPositionData(state.wasmModule, page)
      await state.workerClient.updatePage(pageId, enrichedPage)
    }
  }

  async addPage(page: PenpotPage): Promise<void> {
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
      this.currentPageNodes = page?.children ?? EMPTY_NODES
      this.currentPageNodesMap = page ? flattenPageNodes(page) : EMPTY_MAP
      state.setPageId(nextPageId)
      this.pushToStores()
      if (state.renderer?.isInitialized() && page) {
        await state.renderer.initPage(page)
        state.setViewport(new Viewport())
        if (state.wasmModule && state.workerClient) {
          const enrichedPage = enrichPageWithPositionData(state.wasmModule, page)
          await state.workerClient.updatePage(nextPageId, enrichedPage)
        }
      }
    } else {
      state.setPageId(nextPageId)
    }
  }

  async addNode(node: PenpotNode): Promise<void> {
    const state = useWorkspaceStore.getState()
    const pageId = state.pageId
    const page = pageId ? this.pageMap.get(pageId) : undefined
    if (!pageId || !page) return
    const children = [...(page.children ?? []), node]
    const rootFrame = page.children?.[0]
    const rootId = rootFrame?.id ?? ZERO_UUID
    const addChange: AddObjChange = {
      type: 'add-obj',
      id: node.id,
      obj: node,
      frameId: rootId,
      parentId: rootId,
      index: page.children?.length ?? 0,
    }
    await commitPageUpdateWithChanges({
      pageId,
      updatedPage: { ...page, children },
      changes: [addChange],
    })
  }

  async updateNode(nodeId: string, updates: Partial<PenpotNode>): Promise<void> {
    const state = useWorkspaceStore.getState()
    const pageId = state.pageId
    const page = pageId ? this.pageMap.get(pageId) : undefined
    if (!pageId || !page) return
    const children = (page.children ?? []).map((n: PenpotNode) =>
      n.id === nodeId ? ({ ...n, ...updates } as PenpotNode) : n
    )
    const operations = Object.entries(updates).map(([attr, val]) => ({
      type: 'set' as const,
      attr,
      val,
    }))
    const modChange: ModObjChange = {
      type: 'mod-obj',
      id: nodeId,
      operations,
    }
    await commitPageUpdateWithChanges({
      pageId,
      updatedPage: { ...page, children },
      changes: [modChange],
    })
  }

  async deleteNode(nodeId: string): Promise<void> {
    const state = useWorkspaceStore.getState()
    const pageId = state.pageId
    const page = pageId ? this.pageMap.get(pageId) : undefined
    if (!pageId || !page) return
    const children = (page.children ?? []).filter((n: PenpotNode) => n.id !== nodeId)
    const delChange: DelObjChange = {
      type: 'del-obj',
      id: nodeId,
    }
    await commitPageUpdateWithChanges({
      pageId,
      updatedPage: { ...page, children },
      changes: [delChange],
    })
  }
}
