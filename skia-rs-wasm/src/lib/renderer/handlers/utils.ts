import type { Matrix } from 'penpot-exporter/types'
import { propagateModifiers } from '../api/modifiers'
import { applyTransformToNode } from '../geom/apply-transform-to-node'
import { useWorkspaceStore } from '../store/workspace-store'
import { commitChanges } from '../store/commit'
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
  const { renderer, documentModel } = state
  const module = renderer?.getModule?.()
  if (!module || !documentModel) return
  const pageId =
    state.pageId ?? documentModel.getActiveOrSinglePageId() ?? undefined
  const result = propagateModifiers(module, entries, options?.pixelPrecision ?? 0)
  let builder = emptyChangesBuilder({ pageId })
  for (const { id, transform } of result) {
    const node = documentModel.getNode(id)
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
      pageId: bundle.pageId ?? state.pageId ?? documentModel.getActiveOrSinglePageId() ?? undefined,
    })
  }
}
