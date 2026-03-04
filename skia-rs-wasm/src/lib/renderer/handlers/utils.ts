import type { Matrix, PenpotNode } from '@ui/types';
import { propagateModifiers } from '../api/modifiers';
import { applyTransformToNode } from '../geom/apply-transform-to-node';
import { useWorkspaceStore } from '../store/workspace-store';


export async function applyModifiersAndCommit(
  entries: Array<[string, Matrix]>,
  options?: { pixelPrecision?: number; }
): Promise<void> {
  const state = useWorkspaceStore.getState();
  const { renderer, documentModel } = state;
  const module = renderer?.getModule?.();
  if (!module || !documentModel) return;
  const result = propagateModifiers(module, entries, options?.pixelPrecision ?? 0);
  const updates: Record<string, Partial<PenpotNode>> = {};
  for (const { id, transform } of result) {
    const node = documentModel.getNode(id);
    if (node) {
      const partial = applyTransformToNode(node, transform);
      if (partial) updates[id] = partial;
    }
  }
  if (Object.keys(updates).length > 0) await documentModel.applyNodeUpdates(updates);
}
