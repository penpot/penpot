/**
 * DocumentModel holds document meta, pageMap, and current page derived data.
 * Pushes selectedNodes to workspace store and currentPageNodes/currentPageNodesMap to dev store.
 * Implements IDocumentModel for use by the workspace store.
 */

import type { PenpotDocument, PenpotNode, PenpotPage } from '@penpot-exporter/types'
import { useWorkspaceStore } from './workspace-store'
import { useWorkspaceDevStore } from './workspace-dev-store'
import { Viewport } from '../viewport'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'
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

function getRootFrameChildIds(page: PenpotPage): string[] {
  const ch = page.children ?? []
  if (ch.length <= 1) return []
  return ch
    .slice(1)
    .map((n: PenpotNode) => n.id)
    .filter((id: string | undefined): id is string => id != null)
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

  private async syncRendererAfterUpdate(
    pageId: string,
    oldPage: PenpotPage | undefined,
    updatedPage: PenpotPage
  ): Promise<void> {
    const state = useWorkspaceStore.getState()
    const renderer = state.renderer
    if (!renderer?.isInitialized()) return

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

    if (added.length > 0) {
      for (const node of added) {
        if (node.id) await renderer.addShape(node)
      }
      const rootId = newChildren[0]?.id ?? ROOT_UUID
      renderer.updateParentChildren(rootId, getRootFrameChildIds(updatedPage))
    } else if (deleted.length > 0) {
      const rootId = newChildren[0]?.id ?? ROOT_UUID
      renderer.updateParentChildren(rootId, getRootFrameChildIds(updatedPage))
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

  async commitMove(pageId: string, updatedPage: PenpotPage): Promise<void> {
    const oldPage = this.pageMap.get(pageId)
    this.pageMap.set(pageId, updatedPage)

    if (this.currentPageId === pageId) {
      this.currentPageNodes = updatedPage.children ?? EMPTY_NODES
      this.currentPageNodesMap = flattenPageNodes(updatedPage)
      this.pushToStores()
    }

    const state = useWorkspaceStore.getState()
    if (state.workerClient) await state.workerClient.updatePage(pageId, updatedPage)
    await this.syncRendererAfterUpdate(pageId, oldPage, updatedPage)
  }

  private async updatePageInternal(pageId: string, updatedPage: PenpotPage): Promise<void> {
    const oldPage = this.pageMap.get(pageId)
    this.pageMap.set(pageId, updatedPage)

    if (this.currentPageId === pageId) {
      this.currentPageNodes = updatedPage.children ?? EMPTY_NODES
      this.currentPageNodesMap = flattenPageNodes(updatedPage)
      this.pushToStores()
    }

    const state = useWorkspaceStore.getState()
    if (state.workerClient) await state.workerClient.updatePage(pageId, updatedPage)
    await this.syncRendererAfterUpdate(pageId, oldPage, updatedPage)
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
      }
    } else {
      state.setPageId(nextPageId)
    }
  }

  async addNode(node: PenpotNode): Promise<void> {
    const state = useWorkspaceStore.getState()
    const pageId = state.pageId
    const page = pageId ? this.pageMap.get(pageId) : undefined
    if (!page) return
    const children = [...(page.children ?? []), node]
    await this.updatePageInternal(pageId, { ...page, children })
  }

  async updateNode(nodeId: string, updates: Partial<PenpotNode>): Promise<void> {
    const state = useWorkspaceStore.getState()
    const pageId = state.pageId
    const page = pageId ? this.pageMap.get(pageId) : undefined
    if (!page) return
    const children = (page.children ?? []).map((n: PenpotNode) =>
      n.id === nodeId ? { ...n, ...updates } : n
    )
    await this.updatePageInternal(pageId, { ...page, children })
  }

  async deleteNode(nodeId: string): Promise<void> {
    const state = useWorkspaceStore.getState()
    const pageId = state.pageId
    const page = pageId ? this.pageMap.get(pageId) : undefined
    if (!page) return
    const children = (page.children ?? []).filter((n: PenpotNode) => n.id !== nodeId)
    await this.updatePageInternal(pageId, { ...page, children })
  }
}
