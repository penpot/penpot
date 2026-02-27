/**
 * Helpers for translating Figma NodeChangeEvent into Change[].
 */

import type { Change, AddObjChange, ModObjChange, DelObjChange } from '@skia-rs-wasm/common'
import type { PenpotNode } from '@penpot-exporter/types'

/**
 * Walk up from node to find the nearest frame/section ancestor (for frameId).
 */
export function getFrameNode(node: SceneNode): SceneNode | null {
  let current: BaseNode | null = node.parent
  while (current) {
    if (current.type === 'FRAME' || current.type === 'SECTION') {
      return current as SceneNode
    }
    current = current.parent
  }
  return null
}

export function buildDelObjChange(id: string, pageId?: string): DelObjChange {
  const change: DelObjChange = { type: 'del-obj', id }
  if (pageId != null) change.pageId = pageId
  return change
}

export function buildAddObjChange(
  id: string,
  obj: PenpotNode,
  parentId: string,
  frameId: string,
  index: number | null,
  pageId?: string
): AddObjChange {
  const change: AddObjChange = {
    type: 'add-obj',
    id,
    obj,
    frameId,
    parentId,
    index,
  }
  if (pageId != null) change.pageId = pageId
  return change
}

export function buildModObjChange(
  id: string,
  value: Record<string, unknown>,
  pageId?: string
): ModObjChange {
  const change: ModObjChange = {
    type: 'mod-obj',
    id,
    operations: [{ type: 'assign', value }],
  }
  if (pageId != null) change.pageId = pageId
  return change
}
