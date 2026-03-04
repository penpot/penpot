/**
 * Zustand store for dev-only state (e.g. current page nodes).
 * DocumentModel pushes here when the current page or its nodes change.
 * DevToolbar and other dev UI subscribe to this store.
 */

import { create } from 'zustand'
import type { IndexedNode } from '../../worker/types'

export interface WorkspaceDevState {
  currentPageNodes: IndexedNode[]
  currentPageNodesMap: Record<string, IndexedNode>
  setCurrentPageData: (payload: {
    currentPageNodes: IndexedNode[]
    currentPageNodesMap: Record<string, IndexedNode>
  }) => void
}

const EMPTY_NODES: IndexedNode[] = []
const EMPTY_MAP: Record<string, IndexedNode> = Object.freeze({})

export const useWorkspaceDevStore = create<WorkspaceDevState>()((set) => ({
  currentPageNodes: EMPTY_NODES,
  currentPageNodesMap: EMPTY_MAP,
  setCurrentPageData: (payload) =>
    set({
      currentPageNodes: payload.currentPageNodes,
      currentPageNodesMap: payload.currentPageNodesMap,
    }),
}))
