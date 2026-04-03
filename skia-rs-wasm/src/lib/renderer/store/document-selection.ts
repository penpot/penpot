/**
 * Document selection lives on docProxy; this module syncs WASM selection overlay when selection changes.
 */

import { movePreviewWorldDelta, rotatePreviewDeltaDeg } from '../signals/pointer'
import { querySelectionRect, selectionRect, wasmSelectionRect } from '../signals/selection'
import { docProxy } from './doc-proxy'
import { useWorkspaceStore } from './workspace-store'

function syncSelectionDerived(): void {
  const ids = docProxy.selectedIds
  const renderer = useWorkspaceStore.getState().renderer
  let nextRect: ReturnType<typeof querySelectionRect> = null
  if (ids.size === 0 || !renderer) {
    wasmSelectionRect.value = null
  } else {
    nextRect = querySelectionRect(renderer, ids)
    wasmSelectionRect.value = nextRect
  }
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
  rotatePreviewDeltaDeg.value = 0
  movePreviewWorldDelta.value = { x: 0, y: 0 }
  wasmSelectionRect.value = null
  selectionRect.value = null
}

export function getSelectedIdsSet(): Set<string> {
  return new Set(docProxy.selectedIds)
}
