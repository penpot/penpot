/**
 * Commit partial node updates with paired undo for history (mod-obj assign).
 */

import type { PenpotNode } from 'penpot-exporter/types'
import { appendModObjPair, emptyChangesBuilder, toCommitBundle } from '../../changes/changes-builder'
import { commitChangesPublic } from '../../page-crud'

function snapshotAttrsForUndo(node: PenpotNode, keys: string[]): Record<string, unknown> {
  const snap: Record<string, unknown> = {}
  const rec = node as Record<string, unknown>
  for (const k of keys) {
    const v = rec[k]
    if (v !== undefined) {
      snap[k] = v !== null && typeof v === 'object' ? structuredClone(v as object) : v
    }
  }
  return snap
}

export function rectLayoutPartial(
  x: number,
  y: number,
  width: number,
  height: number,
  rotation: number
): Partial<PenpotNode> {
  const selrect = {
    x,
    y,
    width,
    height,
    x1: x,
    y1: y,
    x2: x + width,
    y2: y + height,
  }
  const points = [
    { x, y },
    { x: x + width, y },
    { x: x + width, y: y + height },
    { x, y: y + height },
  ]
  return {
    x,
    y,
    width,
    height,
    selrect,
    points,
    rotation: rotation !== 0 ? rotation : undefined,
  }
}

export async function commitNodePartialUpdate(
  id: string,
  nodeBefore: PenpotNode,
  partial: Partial<PenpotNode>,
  pageId: string | null | undefined
): Promise<void> {
  const redoAssign: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(partial)) {
    if (v !== undefined) {
      redoAssign[k] = v
    }
  }
  const keys = Object.keys(redoAssign)
  if (keys.length === 0) return

  const undoAssign = snapshotAttrsForUndo(nodeBefore, keys)
  let builder = emptyChangesBuilder({ pageId: pageId ?? undefined })
  builder = appendModObjPair(builder, pageId ?? undefined, id, {
    redoAssign,
    undoAssign,
  })
  const bundle = toCommitBundle(builder)
  await commitChangesPublic({
    redoChanges: bundle.redoChanges,
    undoChanges: bundle.undoChanges,
    pageId: pageId ?? undefined,
  })
}
