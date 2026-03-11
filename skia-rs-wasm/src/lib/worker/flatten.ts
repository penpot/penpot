/**
 * Flatten page to indexed structure for worker selection/index state.
 * Unflatten reconstructs PenpotPage from IndexedPage for getDocument/export.
 */

import type { PenpotNode, PenpotPage } from 'penpot-exporter/types'
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
      name: page.name,
      background: page.background,
      objects: {},
    }
  }

  // Real Penpot pages have children[0].id === ZERO_UUID (the nil-UUID root frame).
  // Figma-exported pages only contain top-level frames as direct children, with no nil-UUID wrapper.
  // The WASM renderer requires tree.get(Uuid::nil()) to succeed, so inject a synthetic root when absent.
  const hasNilRoot = rootFrame.id === ZERO_UUID
  const effectiveChildren: PenpotNode[] = hasNilRoot
    ? children
    : [
        { id: ZERO_UUID, type: 'frame', name: page.name ?? 'Page' } as unknown as PenpotNode,
        ...children,
      ]

  const effectiveRoot = effectiveChildren[0]
  const rootChildIds = effectiveChildren
    .slice(1)
    .map((n: PenpotNode) => n.id)
    .filter((id: string | undefined): id is string => id != null)
  const rootIndexed: IndexedShape = {
    ...effectiveRoot,
    parentId: undefined,
    frameId: effectiveRoot.id,
    shapes: rootChildIds.length > 0 ? rootChildIds : undefined,
  }

  const objects: Record<string, IndexedShape> = {
    [effectiveRoot.id]: rootIndexed,
  }

  for (let i = 1; i < effectiveChildren.length; i++) {
    const node = effectiveChildren[i]
    const childList = (node as { children?: PenpotNode[] }).children
    const childIds = childList?.map(c => c.id).filter((id): id is string => id != null) ?? []

    const indexed: IndexedShape = {
      ...node,
      parentId: effectiveRoot.id,
      frameId: effectiveRoot.id,
      shapes: childIds.length > 0 ? childIds : undefined,
    }
    objects[node.id] = indexed

    if (childList?.length) {
      const resolvedFrameId = node.type === 'frame' ? node.id : effectiveRoot.id
      const childResult = flattenChildrenRec(childList, node.id, resolvedFrameId)
      Object.assign(objects, childResult.objects)
    }
  }

  return {
    id: page.id ?? ZERO_UUID,
    name: page.name,
    background: page.background,
    objects,
  }
}

/**
 * Reconstruct PenpotPage tree from IndexedPage (for getDocument/export).
 * Root frame has parentId undefined; page.children = [rootFrame, ...root.shapes] (flat top level).
 */
export function unflattenIndexedPageToPage(indexed: IndexedPage): PenpotPage {
  const { objects } = indexed
  const root = Object.values(objects).find((o): o is IndexedShape => o.parentId == null)
  if (!root) {
    return {
      id: indexed.id,
      name: indexed.name ?? 'Page',
      background: indexed.background,
      children: [],
    }
  }

  function toNode(shape: IndexedShape): PenpotNode {
    const { shapes, ...rest } = shape
    const children: PenpotNode[] =
      shapes?.map((id: string) => toNode(objects[id]!)).filter(Boolean) ?? []
    return { ...rest, children: children.length > 0 ? children : undefined } as PenpotNode
  }

  const { shapes: _rootShapes, ...rootRest } = root
  const rootNode = { ...rootRest, children: [] } as PenpotNode
  const siblingNodes = (root.shapes ?? [])
    .map((id: string) => objects[id])
    .filter(Boolean)
    .map(toNode)
  return {
    id: indexed.id,
    name: indexed.name ?? 'Page',
    background: indexed.background,
    children: [rootNode, ...siblingNodes],
  }
}
