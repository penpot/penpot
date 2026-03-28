/**
 * Syncs wasmSelectionRect from WASM when selection or renderer changes.
 * Calls refreshWasmSelectionRect() so the overlay reads the current selection rect from WASM.
 */

import { useEffect, useMemo } from 'react'
import { useSnapshot } from 'valtio'
import { docProxy } from '../store/doc-proxy'
import { useWorkspaceStore } from '../store/workspace-store'

export function useWasmSelectionRect(): void {
  const doc = useSnapshot(docProxy)
  const selectionKey = useMemo(() => [...doc.selectedIds].sort().join('\0'), [doc.selectedIds])
  const renderer = useWorkspaceStore((state) => state.renderer)
  const refreshWasmSelectionRect = useWorkspaceStore((state) => state.refreshWasmSelectionRect)

  useEffect(() => {
    refreshWasmSelectionRect()
  }, [selectionKey, renderer, refreshWasmSelectionRect])
}
