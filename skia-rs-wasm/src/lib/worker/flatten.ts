/**
 * Flatten page to indexed structure for worker selection/index state.
 */

import type { PenpotNode, PenpotPage } from 'penpot-exporter/lib'
import { ZERO_UUID } from '@skia-rs-wasm/common'
import type { IndexedPage, IndexedShape } from './types'

function flattenChildrenRec(
  nodes: PenpotNode[] | undefined,
  parentId: string | undefined,
  frameId: string | undefined
): { objects: Record<string, IndexedShape> } {
  const objects: Record<string, IndexedShape> = {}
  if (!nodes?.length) return { objects }

  for (const node of nodes) {
    const childList = (node as { children?: PenpotNode[] }).children
    const childIds = childList?.map(c => c.id).filter((id): id is string => id != null) ?? []

    const indexed: IndexedShape = {
      ...node,
      parentId: parentId ?? node.parentId,
      frameId: frameId ?? parentId ?? node.frameId,
      shapes: childIds.length > 0 ? childIds : undefined,
    }
    objects[node.id] = indexed

    if (childList?.length) {
      const resolvedFrameId = node.type === 'frame' ? node.id : frameId ?? parentId
      const childResult = flattenChildrenRec(childList, node.id, resolvedFrameId)
      Object.assign(objects, childResult.objects)
    }
  }
  return { objects }
}

export function flattenPageToIndexed(page: PenpotPage): IndexedPage {
  const children = page.children ?? []
  const rootFrame = children[0]
  if (!rootFrame) {
    return {
      id: page.id ?? ZERO_UUID,
      objects: {},
    }
  }

  const rootChildIds = children.slice(1).map(n => n.id).filter((id): id is string => id != null)
  const rootIndexed: IndexedShape = {
    ...rootFrame,
    parentId: undefined,
    frameId: rootFrame.id,
    shapes: rootChildIds.length > 0 ? rootChildIds : undefined,
  }

  const objects: Record<string, IndexedShape> = {
    [rootFrame.id]: rootIndexed,
  }

  for (let i = 1; i < children.length; i++) {
    const node = children[i]
    const childList = (node as { children?: PenpotNode[] }).children
    const childIds = childList?.map(c => c.id).filter((id): id is string => id != null) ?? []

    const indexed: IndexedShape = {
      ...node,
      parentId: rootFrame.id,
      frameId: rootFrame.id,
      shapes: childIds.length > 0 ? childIds : undefined,
    }
    objects[node.id] = indexed

    if (childList?.length) {
      const resolvedFrameId = node.type === 'frame' ? node.id : rootFrame.id
      const childResult = flattenChildrenRec(childList, node.id, resolvedFrameId)
      Object.assign(objects, childResult.objects)
    }
  }

  return {
    id: page.id ?? ZERO_UUID,
    objects,
  }
}
