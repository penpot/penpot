import type { Matrix, PenpotNode } from 'penpot-exporter/types'
import { snapshot } from 'valtio'
import { propagateModifiers } from '../api/modifiers'
import { applyTransformToNode } from '../geom/apply-transform-to-node'
import { useWorkspaceStore } from '../store/workspace-store'
import { commitChanges } from '../store/commit'
import { docProxy, getActiveOrSinglePageId } from '../store/doc-proxy'
import {
  appendModObjPair,
  emptyChangesBuilder,
  snapshotGeometryForUndo,
  toCommitBundle,
} from '../../changes/changes-builder'

export async function applyModifiersAndCommit(
  entries: Array<[string, Matrix]>,
  options?: { pixelPrecision?: number }
): Promise<void> {
  const state = useWorkspaceStore.getState()
  const { renderer } = state
  const module = renderer?.getModule?.()
  if (!module) return
  const pageId = state.pageId ?? getActiveOrSinglePageId() ?? undefined

  // snapshot() produces plain non-proxy objects so structuredClone inside
  // snapshotGeometryForUndo does not fail on Valtio proxy sub-objects.
  const docSnap = snapshot(docProxy)
  const pageObjects = pageId ? docSnap.pageMap.get(pageId)?.objects : undefined

  const result = propagateModifiers(module, entries, options?.pixelPrecision ?? 0)
  let builder = emptyChangesBuilder({ pageId })
  for (const { id, transform } of result) {
    const node = pageObjects?.[id] as PenpotNode | undefined
    if (!node) continue
    const undoAssign = snapshotGeometryForUndo(node)
    const partial = applyTransformToNode(node, transform)
    if (!partial) continue
    builder = appendModObjPair(builder, pageId, id, {
      redoAssign: partial as Record<string, unknown>,
      undoAssign,
    })
  }
  const bundle = toCommitBundle(builder)
  if (bundle.redoChanges.length > 0) {
    await commitChanges({
      redoChanges: bundle.redoChanges,
      undoChanges: bundle.undoChanges,
      pageId: bundle.pageId ?? state.pageId ?? getActiveOrSinglePageId() ?? undefined,
    })
  }
}
