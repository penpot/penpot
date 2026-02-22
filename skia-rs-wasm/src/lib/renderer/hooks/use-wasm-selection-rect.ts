/**
 * Syncs wasmSelectionRect from WASM when selection or renderer changes.
 * Calls refreshWasmSelectionRect() so the overlay reads the current selection rect from WASM.
 */

import { useEffect } from 'react'
import { useWorkspaceStore } from '../store/workspace-store'

export function useWasmSelectionRect(): void {
  const selectedIds = useWorkspaceStore((state) => state.selectedIds)
  const renderer = useWorkspaceStore((state) => state.renderer)
  const refreshWasmSelectionRect = useWorkspaceStore((state) => state.refreshWasmSelectionRect)

  useEffect(() => {
    refreshWasmSelectionRect()
  }, [selectedIds, renderer, refreshWasmSelectionRect])
}
