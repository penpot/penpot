/**
 * CRUD operations for document and pages.
 * Updates both document and pageMap in the workspace store.
 */

import { useWorkspaceStore } from './workspace-store'
import { Viewport } from '../viewport'
import type { PenpotDocument, PenpotNode, PenpotPage } from '@penpot-exporter/types'

const ROOT_UUID = '00000000-0000-0000-0000-000000000000'

export function createNewDocument(): PenpotDocument {
  const rootFrame: PenpotNode = {
    id: ROOT_UUID,
    type: 'frame',
    x: 0,
    y: 0,
    width: 800,
    height: 600,
    parentId: undefined,
    selrect: { x1: 0, y1: 0, x2: 800, y2: 600 },
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

function buildPageMap(children: PenpotPage[] | undefined): Map<string, PenpotPage> {
  const map = new Map<string, PenpotPage>()
  if (!children?.length) return map
  for (const page of children) {
    const key = page.id ?? crypto.randomUUID()
    map.set(key, { ...page, id: page.id ?? key })
  }
  return map
}

export async function setDocument(document: PenpotDocument): Promise<void> {
  const pageMap = buildPageMap(document.children)
  const firstPageId =
    document.children?.[0]?.id ?? (pageMap.size ? pageMap.keys().next().value : null)
  useWorkspaceStore.setState({
    document,
    pageMap,
  })
  for (const page of document.children?? []) {
    await addPage(page)
  }
  await setActivePage(firstPageId)
}

export async function setActivePage(pageId: string): Promise<void> {
  const state = useWorkspaceStore.getState()
  if (!state.workerClient || !state.renderer) return
  const page = state.pageMap.get(pageId)
  if (!page) return
  useWorkspaceStore.setState({
    pageId,
  })
  await state.renderer.initPage(page)
  useWorkspaceStore.getState().setViewport(new Viewport())
}

export async function addPage(page: PenpotPage): Promise<void> {
  const state = useWorkspaceStore.getState()
  const doc = state.document
  if (!doc) return
  const key = page.id ?? crypto.randomUUID()
  const pageWithId = { ...page, id: page.id ?? key }
  const children = [...(doc.children ?? []), pageWithId]
  const nextDocument: PenpotDocument = { ...doc, children }
  const nextPageMap = new Map(state.pageMap).set(key, pageWithId)
  useWorkspaceStore.setState({
    document: nextDocument,
    pageMap: nextPageMap,
    pageId: state.pageId ?? key,
  })
  if (state.workerClient) {
    await state.workerClient.addPage(pageWithId)
  }
  if (state.pageId == null && state.renderer?.isInitialized()) {
    await state.renderer.initPage(pageWithId)
    useWorkspaceStore.getState().setViewport(new Viewport())
  }
}

function getRootFrameChildIds(page: PenpotPage): string[] {
  const ch = page.children ?? []
  if (ch.length <= 1) return []
  return ch.slice(1).map((n: PenpotNode) => n.id).filter((id: string | undefined): id is string => id != null)
}

export async function updatePage(page: PenpotPage & { pageId: string }): Promise<void> {
  const state = useWorkspaceStore.getState()
  const doc = state.document
  if (!doc) return
  const { pageId, ...pageData } = page
  const pageWithoutId = pageData as PenpotPage
  const updatedPage = { ...pageWithoutId, id: pageId }
  const children = (doc.children ?? []).map((p) =>
    (p.id ?? '') === pageId ? updatedPage : p
  )
  const nextDocument: PenpotDocument = { ...doc, children }
  const nextPageMap = new Map(state.pageMap).set(pageId, updatedPage)
  useWorkspaceStore.setState({ document: nextDocument, pageMap: nextPageMap })

  if (state.pageId !== pageId) return

  if (state.workerClient) {
    await state.workerClient.updatePage(pageId, updatedPage)
  }

  const renderer = state.renderer
  if (!renderer?.isInitialized()) return

  const oldPage = state.pageMap.get(pageId)
  const oldChildIds = new Set((oldPage?.children ?? []).map((n: PenpotNode) => n.id).filter(Boolean))
  const newChildren = updatedPage.children ?? []
  const newChildIds = newChildren.map((n: PenpotNode) => n.id).filter((id: string | undefined): id is string => id != null)
  const newIdsSet = new Set(newChildIds)

  const added = newChildren.filter((n: PenpotNode) => n.id && !oldChildIds.has(n.id))
  const deleted = (oldPage?.children ?? []).filter((n: PenpotNode) => n.id && !newIdsSet.has(n.id))
  const sameIds = newChildIds.length === (oldPage?.children ?? []).length && added.length === 0 && deleted.length === 0

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

export async function deletePage(pageId: string): Promise<void> {
  const state = useWorkspaceStore.getState()
  const doc = state.document
  if (!doc) return
  const children = (doc.children ?? []).filter((p) => (p.id ?? '') !== pageId)
  const nextPageMap = new Map(state.pageMap)
  nextPageMap.delete(pageId)
  const nextPageId =
    state.pageId === pageId
      ? children[0]?.id ?? nextPageMap.keys().next().value ?? null
      : state.pageId
  useWorkspaceStore.setState({
    document: { ...doc, children },
    pageMap: nextPageMap,
    pageId: nextPageId,
  })
  if (state.pageId === pageId && nextPageId && state.renderer?.isInitialized()) {
    const newPage = nextPageMap.get(nextPageId)
    if (newPage) {
      await state.renderer.initPage(newPage)
      useWorkspaceStore.getState().setViewport(new Viewport())
    }
  }
}

export async function addNode(node: PenpotNode): Promise<void> {
  const state = useWorkspaceStore.getState()
  const pageId = state.pageId
  const page: PenpotPage | null = pageId ? state.pageMap.get(pageId) ?? null : null
  if (!page) return
  const children = [...(page.children ?? []), node]
  await updatePage({ ...page, pageId, children })
}

export async function updateNode(nodeId: string, updates: Partial<PenpotNode>): Promise<void> {
  const state = useWorkspaceStore.getState()
  const pageId = state.pageId
  const page: PenpotPage | null = pageId ? state.pageMap.get(pageId) ?? null : null
  if (!page) return
  const children = (page.children ?? []).map((n: PenpotNode) =>
    n.id === nodeId ? { ...n, ...updates } : n
  )
  await updatePage({ ...page, pageId, children })
}

export async function deleteNode(nodeId: string): Promise<void> {
  const state = useWorkspaceStore.getState()
  const pageId = state.pageId
  const page: PenpotPage | null = pageId ? state.pageMap.get(pageId) ?? null : null
  if (!page) return
  const children = (page.children ?? []).filter((n: PenpotNode) => n.id !== nodeId)
  await updatePage({ ...page, pageId, children })
}
