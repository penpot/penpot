/**
 * Document selection lives on docProxy; this module syncs Zustand-derived UI state (bounds, WASM rect).
 */

import type { IndexedNode } from '../../worker/types'
import { getSelectionBounds } from '../selection-bounds'
import { docProxy, getCurrentPage } from './doc-proxy'
import { useWorkspaceStore } from './workspace-store'

function isIndexedNode(value: IndexedNode | undefined): value is IndexedNode {
  return value !== undefined
}

function syncSelectionDerived(): void {
  const ids = new Set(docProxy.selectedIds)
  const page = getCurrentPage()
  const objects = page?.objects
  const selectedNodes = objects
    ? Array.from(ids)
        .map((id) => objects[id])
        .filter(isIndexedNode)
    : []
  useWorkspaceStore.setState({
    selectionBounds: getSelectionBounds(selectedNodes),
  })
  useWorkspaceStore.getState().refreshWasmSelectionRect()
}

export function setSelectedIds(ids: Set<string>): void {
  docProxy.selectedIds.clear()
  for (const id of ids) {
    docProxy.selectedIds.add(id)
  }
  syncSelectionDerived()
}

export function clearSelection(): void {
  docProxy.selectedIds.clear()
  useWorkspaceStore.setState({
    selectionBounds: null,
    selectionRect: null,
    wasmSelectionRect: null,
    rotatePreviewDeltaDeg: 0,
    movePreviewWorldDelta: { x: 0, y: 0 },
  })
}

export function getSelectedIdsSet(): Set<string> {
  return new Set(docProxy.selectedIds)
}
