/**
 * Zustand store for dev-only state (e.g. current page nodes).
 * DocumentModel pushes here when the current page or its nodes change.
 * DevToolbar and other dev UI subscribe to this store.
 */

import { create } from 'zustand'
import type { PenpotNode } from 'penpot-exporter/lib'

export interface WorkspaceDevState {
  currentPageNodes: PenpotNode[]
  currentPageNodesMap: Record<string, PenpotNode>
  setCurrentPageData: (payload: {
    currentPageNodes: PenpotNode[]
    currentPageNodesMap: Record<string, PenpotNode>
  }) => void
}

const EMPTY_NODES: PenpotNode[] = []
const EMPTY_MAP: Record<string, PenpotNode> = Object.freeze({})

export const useWorkspaceDevStore = create<WorkspaceDevState>()((set) => ({
  currentPageNodes: EMPTY_NODES,
  currentPageNodesMap: EMPTY_MAP,
  setCurrentPageData: (payload) =>
    set({
      currentPageNodes: payload.currentPageNodes,
      currentPageNodesMap: payload.currentPageNodesMap,
    }),
}))
