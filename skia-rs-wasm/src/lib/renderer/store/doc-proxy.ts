import { proxy } from 'valtio'
import { proxyMap, proxySet } from 'valtio/utils'
import type { PenpotDocument } from 'penpot-exporter/types'
import type { IndexedNode, IndexedPage } from '../../worker/types'

export type DocumentMeta = Omit<PenpotDocument, 'children'>

export interface DocState {
  meta: DocumentMeta | null
  pageMap: Map<string, IndexedPage>
  currentPageId: string | null
  /** Reactive Set of selected shape ids (Valtio proxySet). */
  selectedIds: ReturnType<typeof proxySet<string>>
}

export const docProxy = proxy<DocState>({
  meta: null,
  pageMap: proxyMap<string, IndexedPage>(),
  currentPageId: null,
  selectedIds: proxySet<string>(),
})

export function getActiveOrSinglePageId(): string | null {
  if (docProxy.currentPageId) return docProxy.currentPageId
  if (docProxy.pageMap.size === 1) {
    return docProxy.pageMap.keys().next().value ?? null
  }
  return null
}

export function getCurrentPage(): IndexedPage | undefined {
  const pageId = getActiveOrSinglePageId()
  return pageId ? docProxy.pageMap.get(pageId) : undefined
}

export function getPage(pageId: string): IndexedPage | undefined {
  return docProxy.pageMap.get(pageId)
}

export function getNode(nodeId: string): IndexedNode | undefined {
  const page = getCurrentPage()
  return page?.objects[nodeId]
}
